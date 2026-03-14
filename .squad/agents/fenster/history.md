# Fenster's History

## Project Context

**Project:** Distributed URL Shortener  
**Team Owner:** Satyam Baranwal  
**Stack:** Redis, PostgreSQL, Java Spring Boot, Kubernetes, Docker, AKS  
**Role:** DevOps Engineer — containerization, orchestration, AKS deployment

## Infrastructure Stack

- Docker with multi-stage builds
- Kubernetes 1.24+
- Azure Kubernetes Service (AKS)
- PostgreSQL (managed or self-hosted on K8s)
- Redis (managed or self-hosted on K8s)
- Prometheus + Grafana (optional monitoring)

## Learnings

- Created production-ready Dockerfile (multi-stage) for Maven-based Spring Boot build; image uses Eclipse Temurin JRE.
- Added k8s manifests: Deployment (3 replicas), StatefulSets for Postgres and Redis with PVCs, Service, Ingress (AGIC-friendly), HPA, NetworkPolicy, ConfigMap and Secret templates.
- Implemented GitHub Actions CI/CD pipeline to build, push to ACR and deploy to AKS with a smoke test stage.
- Provided Prometheus scrape config and a basic Grafana dashboard template for observability.
- Recommended Azure managed services (Postgres/Redis) for production HA; documented AKS setup guidance in infra/aks-setup.md.

Next steps: confirm build artifact name with McManus; ops to populate secrets, provision ACR and AKS, and decide whether to use managed DB/cache in production.


- 2026-03-14T00:00:00Z: Scribe: Orchestration log added; decisions inbox merged; session kickoff logged.

---

*Updated by Scribe or Fenster after each session*
