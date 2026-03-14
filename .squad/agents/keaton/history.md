# Keaton agent history

## Learnings
- Reviewed infra/azure/postgres-managed.bicep and redis-managed.bicep: geo-redundant backups enabled for Postgres (param), Redis configured as Premium cluster with 6 shards and AOF persistence everysec when enabled.
- VNet integration present via delegated subnets and privateNetworkAccess/subnetId but templates do not create Private Endpoint resources, Private DNS, or NSG rules.
- SSL/TLS enforced: Postgres sslEnforcement=Enabled; Redis minimumTlsVersion=1.2 and non-SSL port disabled.
- PgBouncer is not provisioned by Azure; template emits recommended max connections=100. PgBouncer should be deployed in AKS.
- Monitoring, diagnostic settings, and automated DR orchestration (cross-region failover) are missing and recommended.

