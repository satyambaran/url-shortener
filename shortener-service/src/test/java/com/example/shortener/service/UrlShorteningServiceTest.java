package com.example.shortener.service;

import com.example.shortener.dto.ShortenRequest;
import com.example.shortener.dto.ShortenResponse;
import com.example.shortener.model.UrlMapping;
import com.example.shortener.repository.UrlMappingRepository;
import com.example.shortener.service.impl.UrlShorteningServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class UrlShorteningServiceTest {

    private UrlMappingRepository repo;
    private RedisService redis;
    private UrlShorteningServiceImpl svc;

    @BeforeEach
    public void setup() {
        repo = Mockito.mock(UrlMappingRepository.class);
        redis = Mockito.mock(RedisService.class);
        when(repo.save(any(UrlMapping.class))).thenAnswer(i -> i.getArgument(0));
        svc = new UrlShorteningServiceImpl(repo, redis);
    }

    @Test
    public void testShortenUrl() {
        ShortenRequest req = new ShortenRequest();
        req.setUrl("https://example.com/page");
        req.setExpiresAt(OffsetDateTime.now().plusDays(1));
        ShortenResponse resp = svc.shortenUrl(req);
        assertNotNull(resp);
        assertNotNull(resp.getShortCode());
        assertTrue(resp.getShortUrl().contains(resp.getShortCode()));
    }
}
