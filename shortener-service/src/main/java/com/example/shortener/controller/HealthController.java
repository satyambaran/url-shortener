package com.example.shortener.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import org.springframework.data.redis.core.StringRedisTemplate;

@RestController
@RequestMapping("/health")
public class HealthController {

    @Autowired(required = false)
    private DataSource dataSource;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @GetMapping("/live")
    public Health live() {
        return Health.up().withDetail("status", "alive").build();
    }

    @GetMapping("/ready")
    public Health ready() {
        try {
            if (dataSource != null) dataSource.getConnection().close();
            if (redisTemplate != null) redisTemplate.getConnectionFactory().getConnection().close();
            return Health.up().withDetail("db", "ok").withDetail("redis", "ok").build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
