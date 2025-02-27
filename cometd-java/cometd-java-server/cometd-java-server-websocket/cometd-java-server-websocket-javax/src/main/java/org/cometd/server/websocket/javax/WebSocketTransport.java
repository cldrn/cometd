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
package org.cometd.server.websocket.javax;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.websocket.DeploymentException;
import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.websocket.common.AbstractBayeuxContext;
import org.cometd.server.websocket.common.AbstractWebSocketEndPoint;
import org.cometd.server.websocket.common.AbstractWebSocketTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketTransport extends AbstractWebSocketTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketTransport.class);

    public WebSocketTransport(BayeuxServerImpl bayeux) {
        super(bayeux);
    }

    @Override
    public void init() {
        super.init();

        ServletContext context = (ServletContext)getOption(ServletContext.class.getName());
        if (context == null) {
            throw new IllegalArgumentException("Missing ServletContext");
        }

        String cometdURLMapping = (String)getOption(COMETD_URL_MAPPING_OPTION);
        if (cometdURLMapping == null) {
            throw new IllegalArgumentException("Missing '" + COMETD_URL_MAPPING_OPTION + "' parameter");
        }

        ServerContainer container = (ServerContainer)context.getAttribute(ServerContainer.class.getName());
        if (container == null) {
            throw new IllegalArgumentException("Missing WebSocket ServerContainer");
        }

        // JSR 356 does not support a input buffer size option
        int maxMessageSize = getMaxMessageSize();
        if (maxMessageSize < 0) {
            maxMessageSize = container.getDefaultMaxTextMessageBufferSize();
        }
        container.setDefaultMaxTextMessageBufferSize(maxMessageSize);

        long idleTimeout = getOption(IDLE_TIMEOUT_OPTION, container.getDefaultMaxSessionIdleTimeout());
        container.setDefaultMaxSessionIdleTimeout(idleTimeout);

        String protocol = getProtocol();
        List<String> protocols = protocol == null ? null : Collections.singletonList(protocol);

        Configurator configurator = new Configurator(context);

        for (String mapping : normalizeURLMapping(cometdURLMapping)) {
            ServerEndpointConfig config = ServerEndpointConfig.Builder.create(WebSocketEndPoint.class, mapping)
                    .subprotocols(protocols)
                    .configurator(configurator)
                    .build();
            try {
                container.addEndpoint(config);
            } catch (DeploymentException x) {
                throw new RuntimeException(x);
            }
        }
    }

    protected boolean checkOrigin(String origin) {
        return true;
    }

    protected void modifyHandshake(HandshakeRequest request, HandshakeResponse response) {
    }

    protected Object newWebSocketEndPoint(BayeuxContext bayeuxContext) {
        return new EndPoint(bayeuxContext);
    }

    private static class WebSocketContext extends AbstractBayeuxContext {
        private WebSocketContext(ServletContext context, HandshakeRequest request, Map<String, Object> userProperties) {
            super(context, request.getRequestURI().toString(), request.getQueryString(), request.getHeaders(),
                    request.getParameterMap(), request.getUserPrincipal(), (HttpSession)request.getHttpSession(),
                    // Hopefully these will become a standard, for now they are Jetty specific.
                    (InetSocketAddress)userProperties.get("javax.websocket.endpoint.localAddress"),
                    (InetSocketAddress)userProperties.get("javax.websocket.endpoint.remoteAddress"),
                    WebSocketTransport.retrieveLocales(userProperties), "HTTP/1.1",
                    WebSocketTransport.isSecure(request));
        }
    }

    private static List<Locale> retrieveLocales(Map<String, Object> userProperties) {
        @SuppressWarnings("unchecked")
        List<Locale> locales = (List<Locale>)userProperties.get("javax.websocket.upgrade.locales");
        if (locales == null || locales.isEmpty()) {
            return Collections.singletonList(Locale.getDefault());
        }
        return locales;
    }

    private static boolean isSecure(HandshakeRequest request) {
        String scheme = request.getRequestURI().getScheme();
        return "https".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme);
    }

    private class Configurator extends ServerEndpointConfig.Configurator {
        private final ServletContext servletContext;

        private Configurator(ServletContext servletContext) {
            this.servletContext = servletContext;
        }

        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
            BayeuxContextHolder context = provideContext();
            context.bayeuxContext = new WebSocketContext(servletContext, request, sec.getUserProperties());
            WebSocketTransport.this.modifyHandshake(request, response);
        }

        @Override
        public boolean checkOrigin(String originHeaderValue) {
            return WebSocketTransport.this.checkOrigin(originHeaderValue);
        }

        @Override
        public String getNegotiatedSubprotocol(List<String> supported, List<String> requested) {
            BayeuxContextHolder context = provideContext();
            context.protocolMatches = checkProtocol(supported, requested);
            if (context.protocolMatches) {
                return super.getNegotiatedSubprotocol(supported, requested);
            }
            LOGGER.warn("Could not negotiate WebSocket SubProtocols: server{} != client{}", supported, requested);
            return null;
        }

        @Override
        public List<Extension> getNegotiatedExtensions(List<Extension> installed, List<Extension> requested) {
            Set<Extension> negotiated = new LinkedHashSet<>();
            for (Extension requestedExtension : requested) {
                String name = requestedExtension.getName();
                boolean option = getOption(ENABLE_EXTENSION_PREFIX_OPTION + name, true);
                if (option) {
                    for (Extension installedExtension : installed) {
                        if (installedExtension.getName().equals(name)) {
                            negotiated.add(requestedExtension);
                            break;
                        }
                    }
                }
            }
            return new ArrayList<>(negotiated);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
            BayeuxContextHolder holder = provideContext();
            if (!getBayeux().getAllowedTransports().contains(getName())) {
                throw new InstantiationException("Transport not allowed");
            }
            if (!holder.protocolMatches) {
                throw new InstantiationException("Could not negotiate WebSocket SubProtocols");
            }
            T instance = (T)newWebSocketEndPoint(holder.bayeuxContext);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Created {}", instance);
            }
            holder.clear();
            return instance;
        }

        private boolean checkProtocol(List<String> serverProtocols, List<String> clientProtocols) {
            if (serverProtocols.isEmpty()) {
                return true;
            }

            for (String clientProtocol : clientProtocols) {
                if (serverProtocols.contains(clientProtocol)) {
                    return true;
                }
            }
            return false;
        }

        private BayeuxContextHolder provideContext() {
            BayeuxContextHolder holder = BayeuxContextHolder.holder.get();
            if (holder == null) {
                holder = new BayeuxContextHolder();
                holder.clear();
                BayeuxContextHolder.holder.set(holder);
            }
            return holder;
        }
    }

    private static class BayeuxContextHolder {
        private static final ThreadLocal<BayeuxContextHolder> holder = new ThreadLocal<>();
        private WebSocketContext bayeuxContext;
        private boolean protocolMatches;

        public void clear() {
            BayeuxContextHolder.holder.set(null);
            bayeuxContext = null;
            // Use a sensible default in case getNegotiatedSubprotocol() is not invoked.
            protocolMatches = true;
        }
    }

    private class EndPoint extends WebSocketEndPoint {
        private EndPoint(BayeuxContext bayeuxContext) {
            super(WebSocketTransport.this, bayeuxContext);
        }

        @Override
        protected void writeComplete(AbstractWebSocketEndPoint.Context context, List<ServerMessage> messages) {
            WebSocketTransport.this.writeComplete(context, messages);
        }
    }
}
