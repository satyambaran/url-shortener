AKS Cluster Setup (Guidance)

This document describes recommended AKS configuration. Use Terraform or ARM to provision.

Recommended resources:
- Kubernetes version: 1.24+
- Node pools:
  - system (small, 1-2 nodes)
  - app (vmSize: Standard_D4s_v3 or similar, autoscaling enabled)
- Networking: Azure CNI in a dedicated VNet with subnets for nodes and services
- Storage: use managed disks (Premium SSD) for Postgres PVs; standard for app logs
- Database: prefer Azure Database for PostgreSQL (Flexible Server) for HA and backups; StatefulSet provided as fallback
- Redis: prefer Azure Cache for Redis (Premium) — use StatefulSet only when necessary
- Monitoring: enable Azure Monitor Container Insights; add Prometheus + Grafana for fine-grained metrics
- RBAC: enable Azure AD integration and least-privilege service principals for CI

Note: Provide terraform modules for the above in future work. This document is an operational guide for operators.
