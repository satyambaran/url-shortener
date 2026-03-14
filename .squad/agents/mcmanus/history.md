# McManus's History

## Project Context

**Project:** Distributed URL Shortener  
**Team Owner:** Satyam Baranwal  
**Stack:** Redis, PostgreSQL, Java Spring Boot, Kubernetes, Docker, AKS  
**Role:** Backend Developer — implement Spring Boot APIs and business logic

## Tech Stack

- Java 17+
- Spring Boot 3.x
- PostgreSQL with Spring Data JPA
- Redis with Spring Data Redis
- Testcontainers for integration tests
- JUnit 5 + Mockito

## Learnings

(To be updated as work progresses — document code patterns, API design, data layer decisions, performance considerations)

- 2026-03-14: Added production Spring profile supporting Azure managed Postgres and Redis via environment variables; configured HikariCP (minIdle=5, maxPoolSize=20, idleTimeout=600s) and Jedis pool + 2s timeout with a default of 3 max retries (exposed as app.redis.max-retries). These settings assume Azure-managed services with SSL; SSL flags are bound to environment variables for deployment.

- 2026-03-14: Scaffolded initial multi-module Maven project and bootstrapped the shortener-service module with controllers, services, repositories, DTOs, configuration and unit test skeletons. Redis and PostgreSQL integrations are stubbed; health endpoints and basic exception handling added.

- 2026-03-14T00:00:00Z: Scribe: Orchestration log added; decisions inbox merged; session kickoff logged.

---

*Updated by Scribe or McManus after each session*
