/*
 * Copyright (C) 2005-2008 Jive Software. All rights reserved.
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

package org.jivesoftware.openfire.plugin;

import static org.jivesoftware.openfire.spi.ConnectionManagerImpl.COMPRESSION_FILTER_NAME;
import static org.jivesoftware.openfire.spi.ConnectionManagerImpl.TLS_FILTER_NAME;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.filterchain.DefaultIoFilterChainBuilder;
import org.apache.mina.core.filterchain.IoFilterAdapter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;
import org.apache.mina.transport.socket.SocketAcceptor;
import org.jivesoftware.util.SystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MINA filter that prints to the stdout received XML stanzas before they are actually parsed and
 * also prints XML stanzas as sent to the XMPP entities. Moreover, it also prints information when
 * a session is closed.
 *
 * @author Gaston Dombiak
 */
public class RawPrintFilter extends IoFilterAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RawPrintFilter.class);
    private static final String FILTER_NAME = "rawDebugger";
    private final DebuggerPlugin plugin;
    private final String prefix;
    private final Collection<IoSession> sessions = new ConcurrentLinkedQueue<>();
    private boolean enabled;
    private final SystemProperty<Boolean> enabledProperty;

    RawPrintFilter(final DebuggerPlugin plugin, final String prefix) {
        this.plugin = plugin;
        this.prefix = prefix;
        this.enabledProperty = SystemProperty.Builder.ofType(Boolean.class)
            .setKey(DebuggerPlugin.PROPERTY_PREFIX + prefix.toLowerCase())
            .setDefaultValue(Boolean.TRUE)
            .setDynamic(true)
            .setPlugin(DebuggerPlugin.PLUGIN_NAME)
            .addListener(this::enabled)
            .build();
        this.enabled(enabledProperty.getValue());
    }

    void addFilterToChain(final SocketAcceptor acceptor) {
        if (acceptor == null) {
            LOGGER.debug("Not adding filter '{}' for {} to acceptor that is null.", FILTER_NAME, prefix);
            return;
        }

        final DefaultIoFilterChainBuilder chain = acceptor.getFilterChain();
        if (chain.contains(COMPRESSION_FILTER_NAME)) {
            LOGGER.debug("Adding filter '{}' for {} as the first filter after the compression filter in acceptor {}", FILTER_NAME, prefix, acceptor);
            chain.addAfter(COMPRESSION_FILTER_NAME, FILTER_NAME, this);
        } else if (chain.contains(TLS_FILTER_NAME)) {
            LOGGER.debug("Adding filter '{}' for {} as the first filter after the TLS filter in acceptor {}", FILTER_NAME, prefix, acceptor);
            chain.addAfter(TLS_FILTER_NAME, FILTER_NAME, this);
        } else {
            LOGGER.debug("Adding filter '{}' for {} as the last filter in acceptor {}", FILTER_NAME, prefix, acceptor);
            chain.addLast(FILTER_NAME, this);
        }
    }

    void removeFilterFromChain(final SocketAcceptor acceptor) {
        if (acceptor == null) {
            LOGGER.debug("Not removing filter '{}' for {} from acceptor that is null.", FILTER_NAME, prefix);
            return;
        }

        if (acceptor.getFilterChain().contains(FILTER_NAME)) {
            LOGGER.debug("Removing filter '{}' for {} from acceptor {}", FILTER_NAME, prefix, acceptor);
            acceptor.getFilterChain().remove(FILTER_NAME);
        } else {
            LOGGER.debug("Unable to remove non-existing filter '{}' for {} from acceptor {}", FILTER_NAME, prefix, acceptor);
        }
    }


    @Override
    public void messageReceived(final NextFilter nextFilter, final IoSession session, final Object message) throws Exception {
        // Decode the bytebuffer and print it to the stdout
        if (enabled && message instanceof String && (plugin.isLoggingWhitespace() || !((String) message).isEmpty())) {
            plugin.log(messagePrefix(session, "RECV") + ": " + message);
        }
        // Pass the message to the next filter
        super.messageReceived(nextFilter, session, message);
    }

    private void logSentBuffer(final IoSession session, final IoBuffer ioBuffer) {
        // Keep current position in the buffer
        final int currentPos = ioBuffer.position();
        // Decode buffer
        final CharBuffer charBuffer = StandardCharsets.UTF_8.decode(ioBuffer.buf());
        // Log buffer content
        if (plugin.isLoggingWhitespace() || charBuffer.length() > 0) {
            plugin.log(messagePrefix(session, "SENT") + ": " + charBuffer);
        }
        // Reset to old position in the buffer
        ioBuffer.position(currentPos);
    }

    private String messagePrefix(final IoSession session, final String messageType) {
        return String.format("%s %-16s - %s - (%11s)", prefix, session.getRemoteAddress() == null ? "" : session.getRemoteAddress(), messageType, session.hashCode());
    }

    @Override
    public void messageSent(final NextFilter nextFilter, final IoSession session, final WriteRequest writeRequest) throws Exception {
        if (enabled && writeRequest.getMessage() instanceof IoBuffer) {
            logSentBuffer(session, (IoBuffer) writeRequest.getMessage());
        }
        // Pass the message to the next filter
        super.messageSent(nextFilter, session, writeRequest);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        enabledProperty.setValue(enabled);
    }

    private void enabled(final boolean enabled) {
        this.enabled = enabled;
        LOGGER.info("{} logger {}", prefix, enabled ? "enabled" : "disabled");
    }

    void shutdown() {
        // Remove this filter from sessions that are using it
        for (final IoSession session : sessions) {
            session.getFilterChain().remove(FILTER_NAME);
        }
        sessions.clear();
    }

    @Override
    public void sessionCreated(final NextFilter nextFilter, final IoSession session) throws Exception {
        // Keep track of sessions using this filter
        sessions.add(session);
        if (enabled) {
            // Log that a session was opened
            plugin.log(messagePrefix(session, "OPEN"));
        }
        super.sessionCreated(nextFilter, session);
    }

    @Override
    public void sessionClosed(final NextFilter nextFilter, final IoSession session) throws Exception {
        // Update list of sessions using this filter
        sessions.remove(session);
        if (enabled) {
            // Log that a session was closed
            plugin.log(messagePrefix(session, "CLSD"));
        }
        super.sessionClosed(nextFilter, session);
    }
}
