# Test Strategy for Distributed URL Shortener

## Overview
This document defines the QA strategy for the distributed URL shortener. It covers unit, integration, load, and chaos testing to validate functionality, performance, reliability, and SLAs described in the canonical architecture.

## What's being tested and why
- POST /api/shorten: correctness, idempotency, rate limiting, slug reservation
- GET /{short_code}: redirects, cache hits/misses, bloom filter, expiry
- DELETE /{short_code}: authorization, deletes
- Background workers: write queue, expiry cleanup
- Non-functional: latency (<100ms p99), throughput (1M writes/day), availability (99.9%)

## Test Categories
- Unit Tests (JUnit 5, Mockito): fast, isolated, focus on business logic and edge cases.
- Integration Tests (Testcontainers): real PostgreSQL + Redis to validate end-to-end flows.
- Load Tests (k6): validate SLAs under realistic read/write ratios and concurrency.
- Chaos Tests (Chaos Mesh/Gremlin manifests): validate graceful degradation under component failures.

## Coverage Targets
- Overall coverage: >80%
- Critical paths (idempotency, slug reservation, write durability): 100%

## SLA Validation
- Latency: Redirect p99 < 100ms (asserted in load tests)
- Throughput: System supports 1M writes/day (validated in load scripts and capacity planning)
- Availability: 99.9% uptime target validated via chaos tests and readiness probes

## Risk Matrix (high level)
- Redis outage: risk to slug reservation and caching. Tests: ensure writes are queued and reads fallback to DB.
- PostgreSQL outage: risk to durability. Tests: queue persistence and replay, graceful failures.
- Bloom filter misuse: false positives increase DB load. Tests: measure false positive rate and limit to <1%.
- Rate limiting: false positives block legitimate users. Tests: verify token bucket behavior.

## Integration Test Suite
- Base class: IntegrationTestBase (Testcontainers PostgreSQL + Redis)
- Tests:
  - ShortenerIntegrationTest: POST/GET/DELETE happy paths, duplicates, custom codes, reserved slugs, idempotency
  - CacheConsistencyTest: simulate cache eviction and verify DB consistency
  - ErrorHandlingTest: simulate DB/Redis failures and assert HTTP status codes

## Load Testing
- Script: k6-load-tests/shortener-load-test.js
- Scenario: concurrent users 1000, read/write ratio 100:1, ~12 req/s sustained (1M/day)
- Assertions: p99 <100ms; p95 <50ms; success rate >=99.9%

## Chaos Testing
- Scenarios: Postgres down, Redis down, pod restarts, network latency injection, bloom filter skew
- Expected behavior: reads served from cache where possible; writes queued and replayed; no data loss

## CI/CD Integration
- Run unit + integration tests on every PR
- Fail PR if coverage < 80%
- Nightly/on-demand load tests
- Test reports published as artifacts

## Test Data and Utilities
- TestDataBuilder, fixtures in src/test/resources
- Isolation: use schemas or randomized prefixes per test

## Known Limitations
- Load tests are synthetic; capacity tests should run on representative staging infra
- Chaos tests require Kubernetes cluster and operator (Chaos Mesh/Gremlin)

## Runbook / Troubleshooting
- Common failures and actions: container collisions, port conflicts, timeouts
- When DB is slow: inspect query plans, adjust Hikari pool and PgBouncer

