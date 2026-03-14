package com.example.shortener.service;

import com.example.shortener.dto.ShortenRequest;
import com.example.shortener.dto.ShortenResponse;

public interface UrlShorteningService {
    ShortenResponse shortenUrl(ShortenRequest req);
    String resolveUrl(String shortCode);
    void deleteShortCode(String shortCode);
}
