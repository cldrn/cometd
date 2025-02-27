/*
 * Copyright (c) 2008-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.server.http;

import java.io.IOException;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;
import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.common.BufferingJSONAsyncParser;
import org.cometd.common.JSONContext;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.JSONContextServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AsyncJSONTransport extends AbstractHttpTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncJSONTransport.class);
    private static final String PREFIX = "long-polling.json";
    private static final String NAME = "long-polling";
    private static final int BUFFER_CAPACITY = 512;
    private static final ThreadLocal<byte[]> buffers = ThreadLocal.withInitial(() -> new byte[BUFFER_CAPACITY]);

    public AsyncJSONTransport(BayeuxServerImpl bayeux) {
        super(bayeux, NAME);
        setOptionPrefix(PREFIX);
    }

    @Override
    public boolean accept(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String encoding = request.getCharacterEncoding();
        if (encoding == null) {
            encoding = "UTF-8";
        }
        request.setCharacterEncoding(encoding);
        AsyncContext asyncContext = request.startAsync();
        // Explicitly disable the timeout, to prevent
        // that the timeout fires in case of slow reads.
        asyncContext.setTimeout(0);

        Promise<Void> promise = new Promise<Void>() {
            @Override
            public void succeed(Void result) {
                asyncContext.complete();
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Handling successful");
                }
            }

            @Override
            public void fail(Throwable failure) {
                request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, failure);
                int code = failure instanceof TimeoutException ?
                        getDuplicateMetaConnectHttpResponseCode() :
                        HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                sendError(request, response, code, failure);
                asyncContext.complete();
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Handling failed", failure);
                }
            }
        };

        Context context = new Context(request, response);

        Charset charset = Charset.forName(encoding);
        ReadListener reader = "UTF-8".equals(charset.name()) ?
                new UTF8Reader(context, promise) :
                new CharsetReader(context, promise, charset);
        ServletInputStream input = request.getInputStream();
        input.setReadListener(reader);
    }

    protected void process(String json, Context context, Promise<Void> promise) {
        try {
            try {
                ServerMessage.Mutable[] messages = parseMessages(json);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Parsed {} messages", messages == null ? -1 : messages.length);
                }
                process(messages == null ? null : Arrays.asList(messages), context, promise);
            } catch (ParseException x) {
                handleJSONParseException(context.request, context.response, json, x);
                promise.succeed(null);
            }
        } catch (Throwable x) {
            promise.fail(x);
        }
    }

    private void process(List<ServerMessage.Mutable> messages, Context context, Promise<Void> promise) {
        try {
            if (messages != null) {
                processMessages(context, messages, promise);
            } else {
                promise.succeed(null);
            }
        } catch (Throwable x) {
            promise.fail(x);
        }
    }

    @Override
    protected HttpScheduler suspend(Context context, Promise<Void> promise, ServerMessage.Mutable message, long timeout) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Suspended {}", message);
        }
        context.scheduler = newHttpScheduler(context, promise, message, timeout);
        context.session.notifySuspended(message, timeout);
        return context.scheduler;
    }

    protected HttpScheduler newHttpScheduler(Context context, Promise<Void> promise, ServerMessage.Mutable reply, long timeout) {
        return new AsyncLongPollScheduler(context, promise, reply, timeout);
    }

    @Override
    protected void write(Context context, List<ServerMessage> messages, Promise<Void> promise) {
        HttpServletResponse response = context.response;
        try {
            // Always write asynchronously
            response.setContentType("application/json;charset=UTF-8");
            ServletOutputStream output = response.getOutputStream();
            output.setWriteListener(new Writer(context, messages, promise));
        } catch (Throwable x) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Exception while writing messages", x);
            }
            if (context.scheduleExpiration) {
                scheduleExpiration(context.session, context.metaConnectCycle);
            }
            promise.fail(x);
        }
    }

    protected void writeComplete(Context context, List<ServerMessage> messages) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Messages/replies {}/{} written for {}", messages.size(), context.replies.size(), context.session);
        }
    }

    protected abstract class AbstractReader implements ReadListener {
        private final Context context;
        private final Promise<Void> promise;
        private int total;

        protected AbstractReader(Context context, Promise<Void> promise) {
            this.context = context;
            this.promise = promise;
        }

        @Override
        public void onDataAvailable() throws IOException {
            ServletInputStream input = context.request.getInputStream();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Asynchronous read start from {}", input);
            }
            int maxMessageSize = getMaxMessageSize();
            byte[] buffer = buffers.get();
            while (input.isReady()) {
                int read = input.read(buffer);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Asynchronous read {} bytes from {}", read, input);
                }
                if (read < 0) {
                    break;
                } else {
                    if (maxMessageSize > 0) {
                        total += read;
                        if (total > maxMessageSize) {
                            throw new IOException("Max message size " + maxMessageSize + " exceeded");
                        }
                    }
                    append(buffer, 0, read);
                }
            }
            if (!input.isFinished()) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Asynchronous read pending from {}", input);
                }
            }
        }

        protected abstract void append(byte[] buffer, int offset, int length);

        protected void finish(List<ServerMessage.Mutable> messages) throws IOException {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Asynchronous read end from {}: {}", context.request.getInputStream(), messages);
            }
            process(messages, context, promise);
        }

        protected void finish(String json) throws IOException {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Asynchronous read end from {}: {}", context.request.getInputStream(), json);
            }
            process(json, context, promise);
        }

        @Override
        public void onError(Throwable failure) {
            promise.fail(failure);
        }
    }

    protected class UTF8Reader extends AbstractReader {
        private final JSONContext.AsyncParser parser;

        protected UTF8Reader(Context context, Promise<Void> promise) {
            super(context, promise);
            JSONContextServer jsonContext = getJSONContextServer();
            JSONContext.AsyncParser asyncParser = jsonContext.newAsyncParser();
            if (asyncParser == null) {
                asyncParser = new BufferingJSONAsyncParser(jsonContext);
            }
            this.parser = asyncParser;
        }

        @Override
        protected void append(byte[] bytes, int offset, int length) {
            parser.parse(bytes, offset, length);
        }

        @Override
        public void onAllDataRead() throws IOException {
            List<ServerMessage.Mutable> messages = parser.complete();
            finish(messages);
        }
    }

    protected class CharsetReader extends AbstractReader {
        private byte[] content = new byte[BUFFER_CAPACITY];
        private final Charset charset;
        private int count;

        public CharsetReader(Context context, Promise<Void> promise, Charset charset) {
            super(context, promise);
            this.charset = charset;
        }

        @Override
        protected void append(byte[] buffer, int offset, int length) {
            int size = content.length;
            int newSize = size;
            while (newSize - count < length) {
                newSize <<= 1;
            }

            if (newSize < 0) {
                throw new IllegalArgumentException("Message too large");
            }

            if (newSize != size) {
                byte[] newContent = new byte[newSize];
                System.arraycopy(content, 0, newContent, 0, count);
                content = newContent;
            }

            System.arraycopy(buffer, offset, content, count, length);
            count += length;
        }

        @Override
        public void onAllDataRead() throws IOException {
            finish(new String(content, 0, count, charset));
        }
    }

    protected class Writer implements WriteListener {
        private final Context context;
        private final List<ServerMessage> messages;
        private final Promise<Void> promise;
        private int messageIndex;
        private int replyIndex;
        private boolean needsComma;
        private State state = State.BEGIN;

        protected Writer(Context context, List<ServerMessage> messages, Promise<Void> promise) {
            this.context = context;
            this.messages = messages;
            this.promise = promise;
        }

        @Override
        public void onWritePossible() throws IOException {
            ServletOutputStream output = context.response.getOutputStream();

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Messages/replies {}/{} to write for {}", messages.size(), context.replies.size(), context.session);
            }

            while (true) {
                State current = state;
                switch (current) {
                    case BEGIN: {
                        state = State.HANDSHAKE;
                        if (!writeBegin(output)) {
                            return;
                        }
                        break;
                    }
                    case HANDSHAKE: {
                        state = State.MESSAGES;
                        if (!writeHandshakeReply(output)) {
                            return;
                        }
                        break;
                    }
                    case MESSAGES: {
                        if (!writeMessages(output)) {
                            return;
                        }
                        state = State.REPLIES;
                        break;
                    }
                    case REPLIES: {
                        if (!writeReplies(output)) {
                            return;
                        }
                        state = State.END;
                        break;
                    }
                    case END: {
                        state = State.COMPLETE;
                        if (!writeEnd(output)) {
                            return;
                        }
                        break;
                    }
                    case COMPLETE: {
                        promise.succeed(null);
                        writeComplete(context, messages);
                        return;
                    }
                    default: {
                        throw new IllegalStateException("Could not write in state " + current);
                    }
                }
            }
        }

        private boolean writeBegin(ServletOutputStream output) throws IOException {
            output.write('[');
            return output.isReady();
        }

        private boolean writeHandshakeReply(ServletOutputStream output) throws IOException {
            List<ServerMessage.Mutable> replies = context.replies;
            if (replies.size() > 0) {
                ServerMessage.Mutable reply = replies.get(0);
                if (Channel.META_HANDSHAKE.equals(reply.getChannel())) {
                    if (allowMessageDeliveryDuringHandshake(context.session) && !messages.isEmpty()) {
                        reply.put("x-messages", messages.size());
                    }
                    getBayeux().freeze(reply);
                    output.write(toJSONBytes(reply));
                    needsComma = true;
                    ++replyIndex;
                }
            }
            return output.isReady();
        }

        private boolean writeMessages(ServletOutputStream output) throws IOException {
            try {
                int size = messages.size();
                while (output.isReady()) {
                    if (messageIndex == size) {
                        // Start the interval timeout after writing the
                        // messages since they may take time to be written.
                        startExpiration();
                        return true;
                    } else {
                        if (needsComma) {
                            output.write(',');
                            needsComma = false;
                        } else {
                            ServerMessage message = messages.get(messageIndex);
                            output.write(toJSONBytes(message));
                            needsComma = messageIndex < size;
                            ++messageIndex;
                        }
                    }
                }
                return false;
            } catch (Throwable x) {
                // Start the interval timeout also in case of
                // exceptions to ensure the session can be swept.
                startExpiration();
                throw x;
            }
        }

        private void startExpiration() {
            if (context.scheduleExpiration) {
                scheduleExpiration(context.session, context.metaConnectCycle);
            }
        }

        private boolean writeReplies(ServletOutputStream output) throws IOException {
            List<ServerMessage.Mutable> replies = context.replies;
            int size = replies.size();
            while (output.isReady()) {
                if (replyIndex == size) {
                    return true;
                } else {
                    ServerMessage.Mutable reply = replies.get(replyIndex);
                    if (needsComma) {
                        output.write(',');
                        needsComma = false;
                    } else {
                        getBayeux().freeze(reply);
                        output.write(toJSONBytes(reply));
                        needsComma = replyIndex < size;
                        ++replyIndex;
                    }
                }
            }
            return false;
        }

        private boolean writeEnd(ServletOutputStream output) throws IOException {
            output.write(']');
            return output.isReady();
        }

        @Override
        public void onError(Throwable failure) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Failure writing messages", failure);
            }
            // Start the interval timeout also in case of
            // errors to ensure the session can be swept.
            startExpiration();
            promise.fail(failure);
        }
    }

    private enum State {
        BEGIN, HANDSHAKE, MESSAGES, REPLIES, END, COMPLETE
    }

    private class AsyncLongPollScheduler extends LongPollScheduler {
        private AsyncLongPollScheduler(Context context, Promise<Void> promise, ServerMessage.Mutable reply, long timeout) {
            super(context, promise, reply, timeout);
        }

        @Override
        protected void dispatch(boolean timeout) {
            // Directly succeeding the callback to write messages and replies.
            // Since the write is async, we will never block and thus never delay other sessions.
            getContext().session.notifyResumed(getMessage(), timeout);
            getPromise().succeed(null);
        }
    }
}
