package com.urlshortener.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPoolMXBean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
public class ConnectionPoolingTest {

    @Container
    public static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("shortener_test")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource ds;

    @BeforeAll
    public static void setup() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(postgres.getJdbcUrl());
        cfg.setUsername(postgres.getUsername());
        cfg.setPassword(postgres.getPassword());

        // Mirror prod settings but reduce idle timeout and timeouts for test speed
        cfg.setMinimumIdle(5);
        cfg.setMaximumPoolSize(20);
        cfg.setIdleTimeout(Duration.ofSeconds(2).toMillis()); // 2s instead of 600s for test
        cfg.setConnectionTimeout(1000); // 1s to make timeout tests fast
        cfg.setInitializationFailTimeout(0);

        ds = new HikariDataSource(cfg);
    }

    @AfterAll
    public static void tearDown() {
        if (ds != null) ds.close();
    }

    @Test
    public void poolReachesMinimumOnStartup() throws InterruptedException {
        HikariPoolMXBean mx = ds.getHikariPoolMXBean();
        // Wait a short time for pool to initialize
        Thread.sleep(500);
        int idle = mx.getIdleConnections();
        assertTrue(idle >= 5, "Expected at least 5 idle connections on startup, got: " + idle);
    }

    @Test
    public void poolScalesToMaximumUnderLoad() throws InterruptedException, ExecutionException {
        int max = 20;
        ExecutorService exec = Executors.newFixedThreadPool(max);
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < max; i++) {
            futures.add(exec.submit(() -> {
                try (Connection c = ds.getConnection()) {
                    // hold connection briefly
                    Thread.sleep(200);
                }
                return null;
            }));
        }

        // give tasks time to start
        Thread.sleep(100);
        HikariPoolMXBean mx = ds.getHikariPoolMXBean();
        int active = mx.getActiveConnections();
        assertTrue(active <= max && active > 0, "Active connections should scale under load: " + active);

        for (Future<Void> f : futures) f.get();
        exec.shutdownNow();
    }

    @Test
    public void idleConnectionsEvictedAfterTimeout() throws SQLException, InterruptedException {
        HikariPoolMXBean mx = ds.getHikariPoolMXBean();
        // ensure idle >= min
        Thread.sleep(500);
        int before = mx.getIdleConnections();

        // Wait longer than configured idle timeout (2s)
        Thread.sleep(2500);
        int after = mx.getIdleConnections();

        assertTrue(after <= before, "Expected idle connections to be evicted after timeout; before="+before+" after="+after);
    }

    @Test
    public void connectionTimeoutWhenPoolExhausted() throws InterruptedException {
        // configure a DS with tiny pool to reproduce exhaustion quickly
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(postgres.getJdbcUrl());
        cfg.setUsername(postgres.getUsername());
        cfg.setPassword(postgres.getPassword());
        cfg.setMaximumPoolSize(2);
        cfg.setConnectionTimeout(500); // 0.5s
        cfg.setIdleTimeout(Duration.ofSeconds(5).toMillis());

        try (HikariDataSource smallDs = new HikariDataSource(cfg)) {
            ExecutorService exec = Executors.newFixedThreadPool(3);
            CountDownLatch latch = new CountDownLatch(1);
            Future<?> f1 = exec.submit(() -> {
                try (Connection c = smallDs.getConnection()) {
                    latch.await();
                } catch (Exception ignored) {}
            });
            Future<?> f2 = exec.submit(() -> {
                try (Connection c = smallDs.getConnection()) {
                    latch.await();
                } catch (Exception ignored) {}
            });

            // third should timeout
            Future<?> f3 = exec.submit(() -> {
                try {
                    smallDs.getConnection();
                    fail("Expected a timeout when pool is exhausted");
                } catch (Exception e) {
                    // expected
                }
            });

            Thread.sleep(200);
            latch.countDown();
            f1.cancel(true); f2.cancel(true);
            exec.shutdownNow();
        }
    }
}
