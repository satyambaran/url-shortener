Test Execution Guide

- Run `mvn test` to execute unit and integration tests.
- k6 load tests: BASE_URL env var points to target endpoint.
- Chaos tests: apply YAML manifests to a cluster with Chaos Mesh installed.

Artifacts:
- test-reports/ contains sample outputs and coverage reports.
