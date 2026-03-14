package com.example.shortener.service.impl;

import com.example.shortener.dto.ShortenRequest;
import com.example.shortener.dto.ShortenResponse;
import com.example.shortener.model.UrlMapping;
import com.example.shortener.repository.UrlMappingRepository;
import com.example.shortener.service.RedisService;
import com.example.shortener.service.UrlShorteningService;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;

@Service
public class UrlShorteningServiceImpl implements UrlShorteningService {

    private final UrlMappingRepository repository;
    private final RedisService redisService;

    public UrlShorteningServiceImpl(UrlMappingRepository repository, RedisService redisService) {
        this.repository = repository;
        this.redisService = redisService;
    }

    @Override
    public ShortenResponse shortenUrl(ShortenRequest req) {
        // Stubbed implementation: generates a simple short code and persists
        String code = req.getCustomCode() != null ? req.getCustomCode() : Long.toHexString(System.nanoTime());
        UrlMapping m = new UrlMapping();
        m.setShortCode(code);
        m.setLongUrl(req.getUrl());
        m.setCreatedAt(OffsetDateTime.now());
        m.setExpiresAt(req.getExpiresAt());
        m.setCustomFlag(req.getCustomCode() != null);
        repository.save(m);
        // cache in redis (placeholder)
        redisService.put(code, req.getUrl());
        return new ShortenResponse(code, "/" + code);
    }

    @Override
    public String resolveUrl(String shortCode) {
        // Try cache then DB
        String val = redisService.get(shortCode);
        if (val != null) return val;
        Optional<UrlMapping> maybe = repository.findByShortCode(shortCode);
        return maybe.map(UrlMapping::getLongUrl).orElse(null);
    }

    @Override
    public void deleteShortCode(String shortCode) {
        repository.deleteByShortCode(shortCode);
        redisService.delete(shortCode);
    }
}
