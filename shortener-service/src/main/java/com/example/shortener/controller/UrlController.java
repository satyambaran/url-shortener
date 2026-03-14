package com.example.shortener.controller;

import com.example.shortener.dto.ShortenRequest;
import com.example.shortener.dto.ShortenResponse;
import com.example.shortener.exception.NotFoundException;
import com.example.shortener.service.UrlShorteningService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class UrlController {

    private final UrlShorteningService service;

    public UrlController(UrlShorteningService service) {
        this.service = service;
    }

    @PostMapping("/shorten")
    public ResponseEntity<ShortenResponse> shorten(@Valid @RequestBody ShortenRequest req) {
        ShortenResponse resp = service.shortenUrl(req);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable("shortCode") String shortCode) {
        String target = service.resolveUrl(shortCode);
        if (target == null) throw new NotFoundException("Short code not found");
        return ResponseEntity.status(302).header("Location", target).build();
    }

    @DeleteMapping("/{shortCode}")
    public ResponseEntity<Void> delete(@PathVariable("shortCode") String shortCode) {
        service.deleteShortCode(shortCode);
        return ResponseEntity.noContent().build();
    }
}
