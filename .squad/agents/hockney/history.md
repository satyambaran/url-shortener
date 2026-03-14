# Hockney's History

## Project Context

**Project:** Distributed URL Shortener  
**Team Owner:** Satyam Baranwal  
**Stack:** Redis, PostgreSQL, Java Spring Boot, Kubernetes, Docker, AKS  
**Role:** QA / Tester — fault tolerance, load testing, edge cases, resilience validation

## Testing Stack

- JUnit 5 + Mockito (unit tests)
- Testcontainers (integration tests with real databases)
- JMeter or Gatling (load testing)
- Chaos Monkey or Gremlin (failure injection)
- Prometheus queries (metric validation)

## SLA Targets

(To be specified by Verbal — document expected QPS, latency targets, availability SLA, RTO/RPO)

## Learnings

- Drafted a comprehensive test strategy aligning to the canonical architecture SLAs (<100ms p99, 1M writes/day, 99.9% uptime).
- Created skeleton integration tests using Testcontainers to standardize environment setup (Postgres + Redis).
- Added k6 load script and Chaos Mesh manifests to validate performance and reliability scenarios.
- CI workflow (GitHub Actions) runs unit/integration tests on PRs and schedules load tests.

- Implemented integration tests that validate HikariCP and Jedis pooling behaviors (startup min, scaling, eviction, exhaustion, timeout and recovery) using ephemeral Testcontainers for PostgreSQL and Redis.
- Tests shorten production timeouts for practical execution (idle-timeout reduced to 2s in tests) while preserving behavioral assertions; production values remain in application.yml (600s idle timeout).
- Simulated slow Redis responses via `redis-cli DEBUG SLEEP` inside the container and transient outages by stopping/starting the container to validate retry and recovery behavior.
- E2E workload runs 1200 concurrent operations that exercise DB + Redis pools, assert no connection leaks, and report latency percentiles (p50/p95/p99).

(Will expand with results and observed failure modes as tests are run.)

- 2026-03-14T00:00:00Z: Scribe: Orchestration log added; decisions inbox merged; session kickoff logged.

---

*Updated by Scribe or Hockney after each session*
