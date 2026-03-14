package com.example.shortener.test;

import java.time.Instant;
import java.util.UUID;

public class TestDataBuilder {

    public static String randomLongUrl() {
        return "https://example.com/" + UUID.randomUUID().toString();
    }

    public static String randomOwner() {
        return "owner-" + UUID.randomUUID().toString();
    }

    public static long futureExpirySeconds(int seconds) {
        return Instant.now().plusSeconds(seconds).getEpochSecond();
    }
}
