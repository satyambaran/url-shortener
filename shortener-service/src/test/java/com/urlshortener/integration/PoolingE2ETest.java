package com.urlshortener.integration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
public class PoolingE2ETest {

    @Container
    public static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("shortener_e2e")
            .withUsername("postgres")
            .withPassword("postgres");

    @Container
    public static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static HikariDataSource ds;
    private static JedisPool pool;

    @BeforeAll
    public static void setup() throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(postgres.getJdbcUrl());
        cfg.setUsername(postgres.getUsername());
        cfg.setPassword(postgres.getPassword());
        cfg.setMinimumIdle(5);
        cfg.setMaximumPoolSize(20);
        cfg.setIdleTimeout(Duration.ofSeconds(2).toMillis());
        cfg.setConnectionTimeout(2000);
        cfg.setInitializationFailTimeout(0);

        ds = new HikariDataSource(cfg);

        // create simple table
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute("CREATE TABLE IF NOT EXISTS urls (id SERIAL PRIMARY KEY, key VARCHAR(255), url TEXT)");
        }

        JedisPoolConfig jcfg = new JedisPoolConfig();
        jcfg.setMaxTotal(20);
        jcfg.setMaxIdle(10);
        jcfg.setMinIdle(5);
        pool = new JedisPool(jcfg, redis.getHost(), redis.getMappedPort(6379), 2000);
    }

    @AfterAll
    public static void tearDown() {
        if (ds != null) ds.close();
        if (pool != null) pool.close();
    }

    @Test
    public void concurrentUrlShorteningNoLeaksAndLatencyMeasurement() throws Exception {
        int operations = 1200;
        ExecutorService exec = Executors.newFixedThreadPool(50);
        List<Future<Long>> futures = new ArrayList<>();

        for (int i = 0; i < operations; i++) {
            final int idx = i;
            futures.add(exec.submit(() -> {
                long start = System.nanoTime();
                try (Connection c = ds.getConnection()) {
                    PreparedStatement ps = c.prepareStatement("INSERT INTO urls(key,url) VALUES(?,?) RETURNING id");
                    ps.setString(1, "k"+idx);
                    ps.setString(2, "http://example.com/"+idx);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        int id = rs.getInt(1);
                        try (Jedis j = pool.getResource()) {
                            j.set("url:"+id, "http://example.com/"+idx);
                        }
                    }
                }
                long end = System.nanoTime();
                return TimeUnit.NANOSECONDS.toMillis(end - start);
            }));
        }

        List<Long> latencies = new ArrayList<>();
        for (Future<Long> f : futures) {
            latencies.add(f.get(10, TimeUnit.SECONDS));
        }
        exec.shutdownNow();

        // pool health checks: no leaked active connections
        assertEquals(0, ds.getHikariPoolMXBean().getActiveConnections(), "No active DB connections should be leaked");
        assertEquals(0, pool.getNumActive(), "No active Redis connections should be leaked");

        // compute percentiles
        List<Long> sorted = latencies.stream().sorted().collect(Collectors.toList());
        long p50 = percentile(sorted, 50);
        long p95 = percentile(sorted, 95);
        long p99 = percentile(sorted, 99);

        System.out.println("Latency p50="+p50+"ms p95="+p95+"ms p99="+p99+"ms");

        // basic assertions to ensure latency is within reasonable bounds under test constraints
        assertTrue(p50 < 200, "p50 too high: "+p50);
        assertTrue(p95 < 2000, "p95 too high: "+p95);
        assertTrue(p99 < 5000, "p99 too high: "+p99);
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int)Math.ceil((p / 100.0) * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size()-1));
        return sorted.get(idx);
    }
}
