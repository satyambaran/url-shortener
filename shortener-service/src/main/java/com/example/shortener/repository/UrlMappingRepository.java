package com.example.shortener.repository;

import com.example.shortener.model.UrlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {
    Optional<UrlMapping> findByShortCode(String shortCode);
    List<UrlMapping> findByExpiresAtBefore(OffsetDateTime cutoff);
    void deleteByShortCode(String shortCode);
}
