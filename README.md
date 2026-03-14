# URL Shortener (Spring Boot)

This repository contains a scaffolded Spring Boot 3.x URL shortener service.

Build & Run (development):

- Ensure PostgreSQL and Redis are available (or use Testcontainers for tests).
- From project root run:

  mvn -pl shortener-service -am spring-boot:run

Run tests:

  mvn -pl shortener-service test

Notes:
- This is a scaffold: core business logic and Redis integration are stubbed for later implementation.
- Health endpoints: /health/live and /health/ready

Infrastructure:
- k8s/ holds Kubernetes manifests for AKS deployment (namespace, deployment, services, statefulsets, HPA, ingress).
- Dockerfile is multi-stage and builds a JAR for runtime on Eclipse Temurin JRE.
- CI/CD: .github/workflows/deploy.yml builds, pushes to ACR and deploys to AKS.
See infra/aks-setup.md for AKS provisioning guidance and monitoring/ for Prometheus/Grafana templates.
