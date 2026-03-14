# Distributed URL Shortener: Canonical Architecture

## Executive Summary

This document defines the authoritative architecture for the distributed URL shortener system. The design targets 100M DAU, 1M writes/day, <100ms p99 latency, and 99.9% uptime. The system is engineered for high scalability, availability, fault tolerance, and operational safety. All decisions herein are implementation-ready and binding for McManus, Fenster, Hockney, and downstream teams.

### SLAs & Constraints
- **Throughput:** 1M writes/day, 100M DAU
- **Latency:** <100ms p99 for redirects
- **Availability:** 99.9% uptime
- **Durability:** All writes are durable; no lost URLs
- **Security:** Mitigates enumeration, DDoS, and replay attacks

### Design Philosophy
- Simplicity, atomicity, and resilience
- Explicit trade-offs for durability, performance, and security
- Observability and operational transparency

---

## Core Architecture

### System Flow (Diagram Narrative)
1. **Client** issues POST /api/shorten
2. **API Gateway** (Azure Application Gateway) routes to backend pods
3. **Backend Service** (Spring Boot) handles business logic
4. **Redis** manages atomic slug reservation, idempotency, and write queue
5. **PostgreSQL** stores canonical URL mappings
6. **CDN** caches redirects for performance
7. **Health Probes** monitor liveness/readiness

### Layers
- **Ingress:** Load balancer, SSL termination, path-based routing
- **API:** REST endpoints, rate limiting, authentication
- **Business Logic:** ID generation, slug reservation, expiry, security
- **Persistence:** Redis (queue, cache, idempotency), PostgreSQL (canonical data)
- **Observability:** Logging, tracing, metrics

---

## Data Model

### PostgreSQL Schema
- **urls**: id (BIGINT, sequence), short_code (VARCHAR, unique), long_url (TEXT), created_at, expires_at, owner, metadata
- **Indexes:** short_code (unique), expires_at (for cleanup)

### Redis Key Namespaces
- **slug:reserved:{short_code}**: atomic reservation
- **idempotency:{request_id}**: prevents duplicate short codes
- **queue:writes**: list for write replay
- **rate:ip:{ip}**, **rate:session:{session_id}**: rate limiting
- **bloom:negative**: Bloom filter for DDoS protection

---

## API Endpoints

- **POST /api/shorten**: Create short URL
  - Validates input, checks rate limits, reserves slug atomically, queues write, returns short_code
- **GET /{short_code}**: Redirect
  - Checks Bloom filter, fetches from Redis/PostgreSQL, issues 301/302, applies CDN caching
- **Health Probes**: /health/live, /health/ready
  - Split endpoints for liveness/readiness

---

## Scalability & Distribution

### ID Generation
- **Sequence-based ID generation**: Central broker allocates atomic ranges, 64-bit format, prevents collisions
- **Atomic slug reservation**: Redis Lua script ensures single reservation per short_code
- **Idempotency across pods**: Redis idempotency key, atomic Lua script, prevents duplicate short codes on retries

### Sharding & Coordination
- **PostgreSQL**: Read replicas for scale, pool sizing via PgBouncer/HikariCP
- **Redis**: Single instance with circuit breaker, eventual consistency for slug reservation

---

## Cache & Performance

- **Redis**: Write-through cache for short_code lookups, TTL for expiry, high hit rates
- **Bloom filter**: Protects against negative lookups (DDoS), adaptive sizing, false positive rate managed
- **CDN**: Caches redirects, TTL tuned for load/capacity

---

## Fault Tolerance

- **Queue writes**: Redis list as primary, background replay for durability (AOF/RDB), idempotency guarantees
- **Expiry cleanup**: Cron job batch deletes expired URLs, uses index, logs actions
- **Circuit breakers**: Applied to Redis/PostgreSQL for failover
- **Redundancy**: PostgreSQL read replicas, Redis durability

---

## Security & Resilience

- **Rate limiting**: IP/session granularity, token bucket, Redis key format, endpoint-specific limits
- **Short code enumeration mitigation**: Format-preserving encryption (FPE), rate limiting, logging, circuit breaker
- **DDoS protection**: Bloom filter for negative lookups, adaptive sizing
- **Observability**: Distributed tracing (OpenTelemetry), correlation IDs, span hierarchy, log correlation, sampling

---

## Operations

- **Health probes**: Split liveness/readiness endpoints, frequency and thresholds tuned for rapid detection
- **Monitoring**: Metrics, logs, distributed tracing, p99 latency insights
- **Logging**: Correlated with tracing, security events, rate limit violations
- **Deployment**: Kubernetes (AKS), Docker, rolling updates, draining, SSL termination

---

## Trade-Offs & Rationale

1. **Sequence-based ID generation**: Central broker prevents collisions, enables sharding, but adds failover complexity
2. **PostgreSQL connection pooling**: PgBouncer/HikariCP optimize throughput, circuit breaker prevents overload
3. **Bloom filter DDoS protection**: Reduces negative lookup load, false positives managed, rebuilds required
4. **Rate limiting**: IP/session granularity balances user experience and abuse prevention
5. **Queue writes**: Redis list with background replay ensures durability, idempotency, and simplicity
6. **Expiry cleanup**: Cron job batch deletes maintain storage hygiene, hard deletes for simplicity
7. **301 vs 302 + CDN**: 301 for permanent, 302 for temporary, CDN TTLs tuned for load
8. **Split probes**: Separate liveness/readiness for operational clarity
9. **Atomic slug reservation**: Redis Lua script ensures atomicity, eventual consistency trade-off
10. **Idempotency across pods**: Redis idempotency key prevents duplicate short codes, adds lookup overhead
11. **Load balancer strategy**: Azure Application Gateway (L7) enables path-based routing, SSL termination, health probes, draining
12. **Observability & tracing**: OpenTelemetry, correlation IDs, span hierarchy, sampling for operational transparency
13. **Security: enumeration mitigation**: FPE, rate limiting, logging, circuit breaker protect against attacks

---

## Diagram Description

**System Flow:**
- Client → API Gateway (L7) → Backend Pod
- Backend Pod → Redis (atomic slug reservation, idempotency, queue)
- Backend Pod → PostgreSQL (canonical storage)
- Backend Pod → CDN (redirect caching)
- Health Probes → Backend Pod
- Monitoring/Tracing → All layers

---

## Implementation Guidance

- All downstream teams (McManus, Fenster, Hockney) must reference this document for implementation.
- No deviation from architecture without consensus and update to this canonical document.
- All 13 points are integrated and binding.

---

## Revision History

- Final consolidation complete (Verbal, 2026-03-14)
- Canonical architecture locked
- Reference for all downstream teams
