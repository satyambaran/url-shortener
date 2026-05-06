package com.kv.transport;

import java.util.Map;

/**
 * Inbound message handler. The {@code KVStore.onMessage} method is registered
 * with the transport via this interface so the rest of the system never imports
 * {@code KVStore} from inside the transport (no cycles).
 */
@FunctionalInterface
public interface MessageHandler {
    Map<String, Object> handle(String fromNode, Map<String, Object> message);
}
