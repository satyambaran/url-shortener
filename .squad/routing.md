# Work Routing

How to decide who handles what.

## Routing Table

| Work Type | Route To | Examples |
|-----------|----------|----------|
| Architecture, design, system decisions, scope | Verbal 🏗️ | API design, database schema, deployment strategy, tech choices |
| Spring Boot implementation, APIs, business logic, data layer | McManus 🔧 | Implement shortener endpoints, add caching layer, write services |
| Kubernetes, Docker, AKS, CI/CD, infrastructure | Fenster ⚙️ | Build Dockerfile, deploy to K8s, set up monitoring, AKS networking |
| Testing, QA, load testing, edge cases, fault tolerance validation | Hockney 🧪 | Write integration tests, load test under QPS, verify resilience |
| Code review | Verbal 🏗️ | Review PRs, check architectural alignment, suggest improvements |
| Session logging | Scribe 📋 | Automatic — never needs routing |

## Domain-to-Agent Mapping

- **URL Shortener Core:** McManus (primary) + Verbal (review)
- **Redis/Caching Layer:** McManus (primary) + Fenster (ops)
- **PostgreSQL/Data Layer:** McManus (primary) + Hockney (resilience testing)
- **Spring Boot Bootstrap & Config:** McManus + Verbal (architecture decision)
- **Kubernetes Deployment:** Fenster (primary) + Verbal (architecture decision)
- **Docker Image & Containerization:** Fenster (primary) + McManus (build optimization)
- **AKS Configuration:** Fenster (primary)
- **High Availability Design:** Verbal (architecture) + Fenster (ops) + Hockney (testing)
- **Load Testing & Scalability Validation:** Hockney (primary) + McManus (support)
- **Fault Tolerance & Resilience:** Hockney (testing) + Fenster (infrastructure) + McManus (application)
- **API Documentation:** McManus (primary) + Verbal (review)
- **Monitoring & Observability:** Fenster (primary) + Hockney (test scenarios)
