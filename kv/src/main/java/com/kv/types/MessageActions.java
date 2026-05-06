package com.kv.types;

/**
 * Wire protocol message actions. Strings (not enums) so the wire format stays
 * transport-agnostic and easy to debug.
 */
public final class MessageActions {
    /** coordinator -> replica: persist this versioned value. */
    public static final String PUT_REPLICA = "PUT_REPLICA";
    /** coordinator -> replica: return your local versioned value. */
    public static final String GET_REPLICA = "GET_REPLICA";
    /** coordinator -> replica: you're stale, take this value. */
    public static final String READ_REPAIR = "READ_REPAIR";
    /** Health check (used by tests/demo). */
    public static final String PING = "PING";

    private MessageActions() {}
}
