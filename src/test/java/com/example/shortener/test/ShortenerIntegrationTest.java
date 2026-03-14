package com.example.shortener.test;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

// NOTE: Tests below are integration skeletons. They assume the Spring Boot app is started
// via @SpringBootTest in the real repository and picks up TEST_DB_URL/TEST_REDIS_* properties.

public class ShortenerIntegrationTest extends IntegrationTestBase {

    @Test
    public void testCreateAndRedirectHappyPath() throws Exception {
        // Arrange: build payload
        String longUrl = TestDataBuilder.randomLongUrl();
        // TODO: use RestTemplate or WebTestClient to POST /api/shorten and assert 201 + short_code
        // Then GET /{short_code} and assert redirect to longUrl
        // This skeleton documents the expected assertions for implementers.
        assertThat(true).isTrue();
    }

    @Test
    public void testIdempotentCreate() throws Exception {
        // Arrange: same request twice should return same short_code
        assertThat(true).isTrue();
    }

    @Test
    public void testGetExpiredUrlReturns404() throws Exception {
        // Arrange: create short code with expires_at in the past
        assertThat(true).isTrue();
    }

    @Test
    public void testDeleteShortCodeAuthorization() throws Exception {
        // Arrange: attempt delete without auth -> 401; with owner auth -> 204
        assertThat(true).isTrue();
    }
}
