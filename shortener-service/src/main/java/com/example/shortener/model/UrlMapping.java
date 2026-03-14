package com.example.shortener.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "urls", indexes = {@Index(name = "idx_expires_at", columnList = "expiresAt")})
public class UrlMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 128)
    private String shortCode;

    @Column(nullable = false, columnDefinition = "text")
    private String longUrl;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    private OffsetDateTime expiresAt;

    private boolean customFlag;

    private String userId;

    // Getters and setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getShortCode() {
        return shortCode;
    }

    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public void setLongUrl(String longUrl) {
        this.longUrl = longUrl;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isCustomFlag() {
        return customFlag;
    }

    public void setCustomFlag(boolean customFlag) {
        this.customFlag = customFlag;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
