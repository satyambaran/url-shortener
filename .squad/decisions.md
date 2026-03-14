# Squad Decisions

## [2026-03-14] Squad Initialized

**Decision:** Team composition for distributed URL shortener project  
**Status:** ✅ Accepted  
**By:** Satyam Baranwal

### Context

Building a production-grade URL shortener with:
- High scalability, availability, fault tolerance
- Tech: Redis, PostgreSQL, Java Spring Boot
- Deployment: Kubernetes, Docker on AKS

### Decision

Assembled 6-member team from "The Usual Suspects" universe:
- **Verbal** (Lead/Architect) — system design, code review, decisions
- **McManus** (Backend Dev) — Spring Boot services, business logic
- **Fenster** (DevOps) — Kubernetes, Docker, AKS infrastructure
- **Hockney** (QA/Tester) — fault tolerance, load testing, edge cases
- **Scribe** (Memory) — session logs, decision records
- **Ralph** (Monitor) — work queue, issue triage

### Rationale

Distributed systems require strict architecture + deep backend work + DevOps precision + rigorous testing. The roster covers all four domains with clear ownership and review gates.

## Governance

- All meaningful changes require team consensus
- Document architectural decisions here
- Keep history focused on work, decisions focused on direction
