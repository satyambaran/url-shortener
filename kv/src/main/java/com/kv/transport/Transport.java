package com.kv.transport;

import java.util.Map;

/**
 * Pluggable transport. Mirrors the take-home contract: send a message to a
 * named target, get back a response map, or {@code null} on unreachable.
 * <p>
 * Implementations:
 * <ul>
 * <li>{@link InProcessTransport} -- shared in-memory bus, with fault
 * injection.</li>
 * <li>(future) HTTP / gRPC backends would implement the same contract.</li>
 * </ul>
 */
public interface Transport {
    /** Returns the response, or {@code null} if the target is unreachable. */
    Map<String, Object> send(String targetNode, Map<String, Object> message, String fromNode);

    void register(String nodeId, MessageHandler handler);

    void unregister(String nodeId);
}
