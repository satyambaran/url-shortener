# Verbal's History

## Project Context

**Project:** Distributed URL Shortener  
**Team Owner:** Satyam Baranwal  
**Stack:** Redis, PostgreSQL, Java Spring Boot, Kubernetes, Docker, AKS  
**Non-Functional Requirements:** Highly scalable, highly available, fault-tolerant, distributed

**Goal:** Production-grade system that handles high throughput, minimal latency, graceful degradation.

## Learnings

- SLA targets: 100M DAU, 1M writes/day, <100ms p99, 99.9% uptime
- Key architecture decisions: Redis single instance with circuit breaker, PostgreSQL read replicas, <2 KB short codes
- Design patterns: write-through cache, eventual consistency, circuit breaker
- Capacity planning: ~500M URLs by year 5, ~1TB storage

---

*Updated by Scribe or Verbal after each session*
