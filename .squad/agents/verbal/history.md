# Verbal's History

## Project Context

**Project:** Distributed URL Shortener  
**Team Owner:** Satyam Baranwal  
**Stack:** Redis, PostgreSQL, Java Spring Boot, Kubernetes, Docker, AKS  
**Non-Functional Requirements:** Highly scalable, highly available, fault-tolerant, distributed

**Goal:** Production-grade system that handles high throughput, minimal latency, graceful degradation.

## Learnings

- Final consolidation complete: All 13 architecture points integrated into a single canonical document.
- Canonical architecture locked: .squad/architecture.md is now the source of truth.
- Reference for all downstream teams (McManus, Fenster, Hockney): Implementation must follow this document.

- 9-point architecture refinement:
  1. Sequence-based ID generation with central broker, atomic range allocation, 64-bit format, failover, collision prevention.
  2. PostgreSQL connection pooling (PgBouncer/HikariCP), pool sizing, timeout, circuit breaker, trade-offs.
  3. DDoS mitigation: Bloom filter for negative lookups, adaptive sizing, false positive rate, rebuilds.
  4. Rate limiter: IP/session granularity, token bucket, Redis key format, endpoint-specific limits, SLA.
  5. Queue writes: Redis list primary, background replay, durability via AOF/RDB, idempotency, trade-off simplicity vs. durability.
  6. Cron job: Expiry cleanup, batch deletes, index use, logging, hard delete.
  7. Redirects: 301 vs 302, CDN caching, TTL, load/capacity trade-off.
  8. Liveness/readiness probes: split endpoints, frequency, thresholds, rationale.
  9. Atomic slug reservation: Redis Lua script, single key namespace, atomicity, eventual consistency.

- 4-point security & operations enhancement (points 10–13):
  10. Idempotency across pods: Redis idempotency key, atomic Lua script, prevents duplicate short codes on retries.
  11. Load balancer strategy: Azure Application Gateway (L7), path-based routing, SSL termination, health probes, draining, rate limiting.
  12. Observability: Distributed tracing (OpenTelemetry), correlation IDs, span hierarchy, log correlation, sampling, p99 latency insights.
  13. Security: Short code enumeration mitigation via format-preserving encryption (FPE), rate limiting, logging, circuit breaker.

- Key trade-offs: Idempotency overhead (Redis lookup per write), FPE latency (<1ms per lookup), tracing sampling (1% in prod, 100% in dev/staging).
- Production readiness checklist: All 13 points address scalability, durability, security, observability, and operational safety.

- SLA targets: 100M DAU, 1M writes/day, <100ms p99, 99.9% uptime
- Key architecture decisions: Redis single instance with circuit breaker, PostgreSQL read replicas, <2 KB short codes
- Design patterns: write-through cache, eventual consistency, circuit breaker
- Capacity planning: ~500M URLs by year 5, ~1TB storage

---

*Updated by Scribe or Verbal after each session*
