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

(Will expand with results and observed failure modes as tests are run.)

- 2026-03-14T00:00:00Z: Scribe: Orchestration log added; decisions inbox merged; session kickoff logged.

---

*Updated by Scribe or Hockney after each session*
