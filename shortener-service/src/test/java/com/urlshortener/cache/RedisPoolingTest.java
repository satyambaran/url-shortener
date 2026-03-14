package com.urlshortener.cache;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
public class RedisPoolingTest {

    @Container
    public static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private static JedisPool pool;

    @BeforeAll
    public static void setup() {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(20);
        cfg.setMaxIdle(10);
        cfg.setMinIdle(5);
        cfg.setMaxWait(Duration.ofMillis(500));

        String host = redis.getHost();
        Integer port = redis.getMappedPort(6379);
        pool = new JedisPool(cfg, host, port, 2000);
    }

    @AfterAll
    public static void tearDown() {
        if (pool != null) pool.close();
    }

    @Test
    public void poolInitializesAndTracksUsage() {
        try (Jedis j = pool.getResource()) {
            j.set("__test_key", "1");
            String v = j.get("__test_key");
            assertEquals("1", v);
        }

        assertTrue(pool.getNumIdle() >= 1);
    }

    @Test
    public void timeoutBehaviorOnSlowResponse() throws Exception {
        try (Jedis j = pool.getResource()) {
            // Use Redis DEBUG SLEEP to simulate slow server response (available in this image)
            Thread.sleep(100); // small pause before issuing command
        }

        // In background, ask server to sleep 3s using redis-cli inside container
        redis.execInContainer("redis-cli", "DEBUG", "SLEEP", "3");

        // Client with 2s socket timeout should fail to get response
        Jedis j = null;
        try {
            j = pool.getResource();
            j.configGet("databases"); // should block until server responds
            fail("Expected a timeout due to slow server response");
        } catch (Exception e) {
            // expected
            assertTrue(e.getMessage() != null);
        } finally {
            if (j != null) j.close();
        }
    }

    @Test
    public void maxRetriesBehaviorOnTransientFailures() throws Exception {
        // Simple retry wrapper that attempts up to 3 times
        int attempts = 0;
        final int maxRetries = 3;

        // cause a transient failure by stopping redis then starting it
        redis.stop();

        boolean succeeded = false;
        for (int i = 0; i < maxRetries; i++) {
            attempts++;
            try {
                // try to get a resource (should fail while stopped)
                try (Jedis j = pool.getResource()) {
                    j.ping();
                }
                succeeded = true;
                break;
            } catch (Exception ex) {
                // wait briefly before retry
                Thread.sleep(200);
            }
        }

        // bring redis back
        redis.start();

        // after restart, a final attempt should succeed
        try (Jedis j = pool.getResource()) {
            String pong = j.ping();
            assertEquals("PONG", pong);
        }

        assertEquals(maxRetries, attempts);
    }

    @Test
    public void poolRecoversAfterBriefOutage() throws Exception {
        // Ensure pool is healthy
        try (Jedis j = pool.getResource()) { j.set("k","v"); }

        // brief outage
        redis.stop();
        Thread.sleep(300);
        redis.start();
        Thread.sleep(500);

        try (Jedis j = pool.getResource()) {
            String v = j.get("k");
            // key may be gone but pool should recover and allow connections
            assertTrue(v == null || v.equals("v"));
        }
    }
}
