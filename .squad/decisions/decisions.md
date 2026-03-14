# Decisions

## fenster-aks-managed-db.md

# Recommendation: Use Azure managed PostgreSQL and Redis

Date: 2026-03-14

Summary:
I recommend we use Azure Database for PostgreSQL (Flexible Server) and Azure Cache for Redis (Premium) in production instead of running StatefulSets in-cluster. Managed services provide automated backups, maintenance, HA, and easier scaling which align with our SLA and durability requirements.

Rationale:
- Automated backups and point-in-time recovery reduce DR burden.
- Better performance and simpler ops for production traffic at scale.
- Reduces cluster maintenance surface area (no operator-managed replicas or Promoted failover scripts).

Action:
- Ops to provision managed services in the same VNet as AKS.
- Update k8s manifests to use external service endpoints and remove in-cluster StatefulSets for prod.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>

## hockney-test-strategy.md

# Hockney Test Strategy Inbox

Decision: Propose test strategy and CI integration for the URL shortener.

Summary:
- Use Testcontainers for integration tests (Postgres + Redis)
- Run unit+integration tests on every PR; nightly load tests via GitHub Actions
- Require coverage gate: >=80%

Rationale: Fast feedback in PRs, realistic integration tests without requiring infra, and scheduled load tests to validate SLAs.

## mcmanus-scaffold.md

---
title: "Scaffolded Spring Boot multi-module choice"
author: McManus
---

Decided to scaffold a Maven multi-module parent with a single module `shortener-service` to keep future submodules (api, worker, admin) possible. Implementation is intentionally minimal: application code lives in shortener-service, Redis/Postgres integrations are stubbed for full implementation in subsequent tasks.
