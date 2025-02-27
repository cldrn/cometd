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
package org.cometd.client.http;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.MarkedReference;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.server.AbstractServerTransport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class LazyChannelAndMessageTest extends ClientServerTest {
    @Test
    public void testLazyChannelWithGlobalTimeout() throws Exception {
        long globalLazyTimeout = 1000;
        start(new HashMap<String, String>() {{
            put(AbstractServerTransport.MAX_LAZY_TIMEOUT_OPTION, String.valueOf(globalLazyTimeout));
        }});

        String channelName = "/testLazy";
        MarkedReference<ServerChannel> channel = bayeux.createChannelIfAbsent(channelName, c -> {
            c.setPersistent(true);
            c.setLazy(true);
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        client.waitFor(5000, BayeuxClient.State.CONNECTED);

        CountDownLatch subscribeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener((ClientSessionChannel.MessageListener)(c, m) -> subscribeLatch.countDown());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong begin = new AtomicLong();
        client.getChannel(channelName).subscribe((c, m) -> {
            if (m.getDataAsMap() == null) {
                return;
            }
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin.get());
            long accuracy = globalLazyTimeout / 10;
            assertTrue(elapsed >= globalLazyTimeout - accuracy, "Expected " + elapsed + " >= " + globalLazyTimeout);
            latch.countDown();
        });
        // Make sure we are subscribed so that there are no
        // pending responses that may return the lazy message
        assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        // Wait for the /meta/connect to establish
        TimeUnit.MILLISECONDS.sleep(1000);

        begin.set(System.nanoTime());
        // Cannot publish from the client, as there will always be the "meta"
        // publish response to send, so the lazy message will be sent with it.
        channel.getReference().publish(null, new HashMap<>(), Promise.noop());

        assertTrue(latch.await(2 * globalLazyTimeout, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testLazyChannelWithChannelTimeout() throws Exception {
        long channelLazyTimeout = 1000;
        long globalLazyTimeout = channelLazyTimeout * 4;
        start(new HashMap<String, String>() {{
            put(AbstractServerTransport.MAX_LAZY_TIMEOUT_OPTION, String.valueOf(globalLazyTimeout));
        }});

        String channelName = "/testLazy";
        MarkedReference<ServerChannel> channel = bayeux.createChannelIfAbsent(channelName, c -> {
            c.setLazyTimeout(channelLazyTimeout);
            c.setPersistent(true);
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        client.waitFor(5000, BayeuxClient.State.CONNECTED);

        CountDownLatch subscribeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener((ClientSessionChannel.MessageListener)(c, m) -> subscribeLatch.countDown());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong begin = new AtomicLong();
        client.getChannel(channelName).subscribe((c, m) -> {
            if (m.getDataAsMap() == null) {
                return;
            }
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin.get());
            assertTrue(elapsed < globalLazyTimeout / 2);
            long accuracy = channelLazyTimeout / 10;
            assertTrue(elapsed >= channelLazyTimeout - accuracy, "Expected " + elapsed + " >= " + channelLazyTimeout);
            latch.countDown();
        });
        // Make sure we are subscribed so that there are no
        // pending responses that may return the lazy message
        assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        // Wait for the /meta/connect to establish
        TimeUnit.MILLISECONDS.sleep(1000);

        begin.set(System.nanoTime());
        // Cannot publish from the client, as there will always be the "meta"
        // publish response to send, so the lazy message will be sent with it.
        channel.getReference().publish(null, new HashMap<>(), Promise.noop());

        assertTrue(latch.await(2 * globalLazyTimeout, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testLazyChannelsWithDifferentChannelTimeouts() throws Exception {
        long channelLazyTimeout = 1000;
        long globalLazyTimeout = channelLazyTimeout * 4;
        start(new HashMap<String, String>() {{
            put(AbstractServerTransport.MAX_LAZY_TIMEOUT_OPTION, String.valueOf(globalLazyTimeout));
        }});

        String shortLazyChannelName = "/shortLazy";
        MarkedReference<ServerChannel> shortLazyChannel = bayeux.createChannelIfAbsent(shortLazyChannelName, channel -> {
            channel.setLazyTimeout(channelLazyTimeout);
            channel.setPersistent(true);
        });

        String longLazyChannelName = "/longLazy";
        MarkedReference<ServerChannel> longLazyChannel = bayeux.createChannelIfAbsent(longLazyChannelName, channel -> {
            channel.setLazyTimeout(globalLazyTimeout);
            channel.setPersistent(true);
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        client.waitFor(5000, BayeuxClient.State.CONNECTED);

        CountDownLatch subscribeLatch = new CountDownLatch(2);
        client.getChannel(Channel.META_SUBSCRIBE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> subscribeLatch.countDown());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong begin = new AtomicLong();
        ClientSessionChannel.MessageListener messageListener = (channel, message) -> {
            if (message.getDataAsMap() == null) {
                return;
            }
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin.get());
            assertTrue(elapsed < globalLazyTimeout / 2);
            long accuracy = channelLazyTimeout / 10;
            assertTrue(elapsed >= channelLazyTimeout - accuracy, "Expected " + elapsed + " >= " + channelLazyTimeout);
            latch.countDown();
        };
        client.getChannel(shortLazyChannelName).subscribe(messageListener);
        client.getChannel(longLazyChannelName).subscribe(messageListener);
        // Make sure we are subscribed so that there are no
        // pending responses that may return the lazy message
        assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        // Wait for the /meta/connect to establish
        TimeUnit.MILLISECONDS.sleep(1000);

        begin.set(System.nanoTime());
        // Cannot publish from the client, as there will always be the "meta"
        // publish response to send, so the lazy message will be sent with it.
        // Send first the long lazy and then the short lazy, to verify that
        // timeouts are properly respected.
        longLazyChannel.getReference().publish(null, new HashMap<>(), Promise.noop());
        shortLazyChannel.getReference().publish(null, new HashMap<>(), Promise.noop());

        assertTrue(latch.await(2 * globalLazyTimeout, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testServerSessionDeliverDataOnLazyChannelDeliversImmediately() throws Exception {
        // ServerSession.deliver(ServerSession sender, String channel, Object data) has
        // the semantic that the channel is intended for the remote end, and as such
        // it should not do any logic related to lazyness, which belongs to the server.

        long globalLazyTimeout = 1000;
        start(new HashMap<String, String>() {{
            put(AbstractServerTransport.MAX_LAZY_TIMEOUT_OPTION, String.valueOf(globalLazyTimeout));
        }});

        String channelName = "/lazyDeliverData";
        MarkedReference<ServerChannel> channel = bayeux.createChannelIfAbsent(channelName, c -> {
            c.setPersistent(true);
            c.addListener(new ServerChannel.MessageListener() {
                @Override
                public boolean onMessage(ServerSession from, ServerChannel channel1, ServerMessage.Mutable message) {
                    for (ServerSession subscriber : channel1.getSubscribers()) {
                        subscriber.deliver(from, message.getChannel(), message.getData(), Promise.noop());
                    }
                    return false;
                }
            });
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        client.waitFor(5000, BayeuxClient.State.CONNECTED);

        CountDownLatch subscribeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener((ClientSessionChannel.MessageListener)(c, m) -> subscribeLatch.countDown());
        AtomicLong begin = new AtomicLong();
        CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(channelName).subscribe((c, m) -> {
            if (m.getDataAsMap() == null) {
                return;
            }
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin.get());
            // Must be delivered immediately
            assertTrue(elapsed < globalLazyTimeout / 2);
            latch.countDown();
        });
        // Make sure we are subscribed so that there are no
        // pending responses that may return the lazy message
        assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        // Wait for the /meta/connect to establish
        TimeUnit.MILLISECONDS.sleep(1000);

        // Cannot publish from the client, as there will always be the "meta"
        // publish response to send, so the lazy message will be sent with it.
        // Send first the long lazy and then the short lazy, to verify that
        // timeouts are properly respected.
        begin.set(System.nanoTime());
        channel.getReference().publish(null, new HashMap<>(), Promise.noop());

        assertTrue(latch.await(globalLazyTimeout * 2, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testServerSessionDeliverLazyMessageOnLazyChannelDeliversLazily() throws Exception {
        long globalLazyTimeout = 1000;
        start(new HashMap<String, String>() {{
            put(AbstractServerTransport.MAX_LAZY_TIMEOUT_OPTION, String.valueOf(globalLazyTimeout));
        }});

        String channelName = "/lazyDeliverMessage";
        MarkedReference<ServerChannel> channel = bayeux.createChannelIfAbsent(channelName, c -> {
            c.setPersistent(true);
            c.addListener(new ServerChannel.MessageListener() {
                @Override
                public boolean onMessage(ServerSession from, ServerChannel channel1, ServerMessage.Mutable message) {
                    ServerMessage.Mutable newMessage = bayeux.newMessage();
                    newMessage.setChannel(message.getChannel());
                    newMessage.setData(message.getData());
                    // Mark the message as lazy
                    newMessage.setLazy(true);
                    for (ServerSession subscriber : channel1.getSubscribers()) {
                        subscriber.deliver(from, newMessage, Promise.noop());
                    }
                    return false;
                }
            });
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        client.waitFor(5000, BayeuxClient.State.CONNECTED);

        CountDownLatch subscribeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener((ClientSessionChannel.MessageListener)(c, m) -> subscribeLatch.countDown());
        AtomicLong begin = new AtomicLong();
        CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(channelName).subscribe((c, m) -> {
            if (m.getDataAsMap() == null) {
                return;
            }
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin.get());
            // Must be delivered lazily
            long accuracy = globalLazyTimeout / 10;
            assertTrue(elapsed > globalLazyTimeout - accuracy);
            latch.countDown();
        });
        // Make sure we are subscribed so that there are no
        // pending responses that may return the lazy message
        assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        // Wait for the /meta/connect to establish
        TimeUnit.MILLISECONDS.sleep(1000);

        // Cannot publish from the client, as there will always be the "meta"
        // publish response to send, so the lazy message will be sent with it.
        // Send first the long lazy and then the short lazy, to verify that
        // timeouts are properly respected.
        begin.set(System.nanoTime());
        channel.getReference().publish(null, new HashMap<>(), Promise.noop());

        assertTrue(latch.await(globalLazyTimeout * 2, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testQueueFullOfLazyMessagesIsNotDelivered() throws Exception {
        long globalLazyTimeout = 1000;
        start(new HashMap<String, String>() {{
            put(AbstractServerTransport.MAX_LAZY_TIMEOUT_OPTION, String.valueOf(globalLazyTimeout));
        }});

        String channelName = "/testQueueLazy";
        MarkedReference<ServerChannel> serverChannel = bayeux.createChannelIfAbsent(channelName, channel -> {
            channel.setLazy(true);
            channel.setPersistent(true);
        });

        BayeuxClient client = newBayeuxClient();
        AtomicLong begin = new AtomicLong();
        CountDownLatch subscribeLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> client.getChannel(channelName).subscribe((c, m) -> {
            if (m.getDataAsMap() == null) {
                return;
            }
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin.get());
            // Must be delivered lazily.
            long accuracy = globalLazyTimeout / 10;
            assertTrue(elapsed > globalLazyTimeout - accuracy);
            messageLatch.countDown();
        }, subscribeReply -> subscribeLatch.countDown()));
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener() {
            private final AtomicInteger connects = new AtomicInteger();

            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                int connects = this.connects.incrementAndGet();
                if (connects == 1) {
                    // Make sure the server has processed the subscription.
                    assertTrue(awaitLatch(subscribeLatch, 2000));
                    // Then add a lazy message on the queue while the /meta/connect is on the client.
                    begin.set(System.nanoTime());
                    serverChannel.getReference().publish(null, new HashMap<>(), Promise.noop());
                }
            }
        });

        client.handshake();
        client.waitFor(5000, BayeuxClient.State.CONNECTED);

        assertTrue(messageLatch.await(globalLazyTimeout * 2, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testLazynessIsNotInheritedFromParentChannel() throws Exception {
        long globalLazyTimeout = 1000;
        start(new HashMap<String, String>() {{
            put(AbstractServerTransport.MAX_LAZY_TIMEOUT_OPTION, String.valueOf(globalLazyTimeout));
        }});

        String parentChannelName = "/foo";
        CountDownLatch latch = new CountDownLatch(1);
        bayeux.createChannelIfAbsent(parentChannelName, channel -> {
            channel.setPersistent(true);
            channel.setLazy(true);
        });

        String childChannelName = parentChannelName + "/bar";
        MarkedReference<ServerChannel> childChannel = bayeux.createChannelIfAbsent(childChannelName, channel -> {
            channel.setPersistent(true);
            channel.addListener(new ServerChannel.MessageListener() {
                @Override
                public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                    Assertions.assertFalse(message.isLazy());
                    latch.countDown();
                    return true;
                }
            });
        });
        childChannel.getReference().publish(null, "data", Promise.noop());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testLazynessIsInheritedFromWildChannel() throws Exception {
        long globalLazyTimeout = 1000;
        start(new HashMap<String, String>() {{
            put(AbstractServerTransport.MAX_LAZY_TIMEOUT_OPTION, String.valueOf(globalLazyTimeout));
        }});

        String parentChannelName = "/foo";
        String wildChannelName = parentChannelName + "/*";
        CountDownLatch latch = new CountDownLatch(1);
        bayeux.createChannelIfAbsent(wildChannelName, channel -> {
            channel.setPersistent(true);
            channel.setLazy(true);
        });

        String childChannelName = parentChannelName + "/bar";
        MarkedReference<ServerChannel> childChannel = bayeux.createChannelIfAbsent(childChannelName, channel -> {
            channel.setPersistent(true);
            channel.addListener(new ServerChannel.MessageListener() {
                @Override
                public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                    assertTrue(message.isLazy());
                    latch.countDown();
                    return true;
                }
            });
        });
        childChannel.getReference().publish(null, "data", Promise.noop());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    private boolean awaitLatch(CountDownLatch latch, long millis) {
        try {
            return latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException x) {
            throw new RuntimeException(x);
        }
    }
}
