package com.example.shortener.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class IntegrationTestBase {

    protected static PostgreSQLContainer<?> postgres;
    protected static GenericContainer<?> redis;

    @BeforeAll
    public static void startContainers() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                .withDatabaseName("shortener_test")
                .withUsername("test")
                .withPassword("test");
        postgres.start();

        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        redis.start();

        System.setProperty("TEST_DB_URL", postgres.getJdbcUrl());
        System.setProperty("TEST_DB_USER", postgres.getUsername());
        System.setProperty("TEST_DB_PASSWORD", postgres.getPassword());
        System.setProperty("TEST_REDIS_HOST", redis.getHost());
        System.setProperty("TEST_REDIS_PORT", String.valueOf(redis.getFirstMappedPort()));
    }

    @AfterAll
    public static void stopContainers() {
        if (redis != null) redis.stop();
        if (postgres != null) postgres.stop();
    }
}
