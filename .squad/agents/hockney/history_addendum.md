## Learnings

- Drafted a comprehensive test strategy aligning to the canonical architecture SLAs (<100ms p99, 1M writes/day, 99.9% uptime).
- Created skeleton integration tests using Testcontainers to standardize environment setup (Postgres + Redis).
- Added k6 load script and Chaos Mesh manifests to validate performance and reliability scenarios.
- CI workflow (GitHub Actions) runs unit/integration tests on PRs and schedules load tests.

