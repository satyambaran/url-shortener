package com.example.shortener.dto;

import java.time.OffsetDateTime;

public class ShortenRequest {
    private String url;
    private String customCode;
    private OffsetDateTime expiresAt;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getCustomCode() { return customCode; }
    public void setCustomCode(String customCode) { this.customCode = customCode; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
}
