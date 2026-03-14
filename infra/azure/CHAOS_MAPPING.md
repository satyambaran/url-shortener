# Chaos Tests → Azure Feature Mapping

## Overview
This document maps chaos-test scenarios to Azure resource behaviors (as defined in infra/azure/*.bicep), expected recovery behavior, estimated RTO/RPO, and mitigations for any gaps.

---

## postgres-down.yaml
- Chaos: pod-kill targeting Postgres pods (chaos-mesh simulation).
- Azure feature mapping:
  - Template uses Microsoft.DBforPostgreSQL/flexibleServers with highAvailability.mode = 'Zone' (intra-region zone redundant failover).
  - backup.geoRedundantBackup = true (param enabled) but this is for backups, not automatic cross-region failover.
  - network: delegatedSubnetResourceId + privateNetworkAccess = 'Enabled' provides VNet integration (private access), but the template does not create Private Endpoint resources or explicit NSG rules.
- Expected behavior:
  - For infrastructure failures within the same region/zone, Flexible Server zone-HA provides failover to standby (seconds-to-minutes depending on failure type).
  - For AZ-level failure, HA Zone should provide rapid failover; for complete region failure, recovery requires restore from geo-redundant backup (manual or scripted restore) — no automatic cross-region failover configured.
- Estimated RTO/RPO:
  - RTO (zone failover): ~30s–5min (typical but depends on failure and Azure internal behavior).
  - RPO (HA within region): near-zero for in-region replica; for geo-restore from backups, RPO depends on backup frequency — template uses daily backups with backupRetentionDays (default 7) and geoRedundantBackup=true, so RPO could be up to the last backup unless continuous WAL replication is configured (not in template).
- Gaps & mitigations:
  - No explicit Private Endpoint resource nor Private DNS entries in template — recommend creating privateEndpoints + Private DNS zones and link to VNet.
  - No NSG rules defined — ensure delegated subnets have NSGs restricting ingress/egress.
  - Add automated runbooks for geo-restore and test restores regularly.

---

## redis-down.yaml
- Chaos: pod-kill targeting Redis pods.
- Azure feature mapping:
  - Uses Microsoft.Cache/Redis Premium with sku.family = 'P' and shardCount = 6 (clustering), capacity=1 (~1GB shards), clusteringPolicy='enterprise'.
  - enableNonSslPort = false and minimumTlsVersion = '1.2' enforce TLS.
  - persistence configured: aofEnabled:true and aofFrequency: 'everysec' when enablePersistence=true.
  - subnetId param provides VNet integration but template does not create Private Endpoint resources or NSG rules.
- Expected behavior:
  - Azure Cache for Redis (Premium/Enterprise) provides automatic failover between primary and replicas; for clustered Enterprise tier, node failover is automatic.
  - With AOF everysec, expected potential data loss up to ~1 second on failure.
- Estimated RTO/RPO:
  - RTO (failover): typically < 30s to low minutes depending on topology.
  - RPO: ~1s (AOF everysec) if persistence is enabled; if persistence is disabled, data loss is possible for in-memory updates.
- Gaps & mitigations:
  - Validate that shardCount=6 & capacity=1 are available in target region — template notes this.
  - Add Azure Monitor diagnostics and alerting for replication/eviction events.

---

## network-latency.yaml
- Chaos: injects 100ms latency to Postgres internal traffic in cluster tests; baseline expectation is sub-5ms within same VNet/region.
- Azure feature mapping:
  - VNet integration (delegated subnets/private endpoints) keeps traffic on Azure backbone; expected intra-VNet latency typically <5ms.
- Expected behavior:
  - Under normal operation, RT latency to managed services should be <5ms; test injecting 100ms is outside normal SLA and should degrade app p99 latency.
- Gaps & mitigations:
  - Add synthetic monitoring (Azure Monitor, Application Insights) to track DB/Redis latency and set alerts at e.g., 5ms/10ms thresholds.

---

## pod-restart.yaml
- Chaos: backend pod restarts; app must reconnect to managed services without state loss.
- Azure feature mapping:
  - Managed services are external and durable; Spring Boot HikariCP/Jedis pools must reconnect on restart. application.yml includes sensible pool settings and SSL options.
- Expected behavior:
  - Pod restart should cause transient connection errors; app should recover within its connection-retry + pool warm-up window. No durable local state should be required.
- Estimated RTO/RPO:
  - RTO (pod restart and reconnection): seconds to tens of seconds depending on readiness/liveness probes and connection pool timeouts.
  - RPO: zero for Postgres (committed writes persisted); for Redis, depends on persistence/AOF settings.
- Gaps & mitigations:
  - Ensure readiness probe waits for DB connectivity if required; add exponential backoff retry logic in app where applicable.

---

## Cross-cutting Gaps Found
- Monitoring/Alerting: templates do not provision diagnosticSettings, Log Analytics, or alert rules for DB/Redis metrics (replication lag, failover, cache misses, persistence failures). Add Azure Monitor diagnostic settings and alerts.
- Private Endpoints & DNS: templates rely on subnet delegation/privateNetworkAccess but do not create Private Endpoint resources nor Private DNS zones — recommend adding them to enforce private connectivity and simplify name resolution.
- NSG Rules: templates assume pre-created subnets with NSGs; make this explicit or create/refer to NSG resources.
- Certificate/Key Management: no Key Vault integration for certs/secrets; recommend storing DB passwords and any certificates in Key Vault and using system-assigned identities where possible.
- PgBouncer: Postgres template only emits a recommended connection value; PgBouncer must be deployed in AKS with max client connections = 100 and configured TLS. Consider a Helm chart or Deployment in infra repo.
- Automated DR: geoRedundantBackup=true gives geo backups but does not provide automatic cross-region failover. Recommend adding cross-region read replicas or Azure Backup/automation for orchestrated failover.
- Rate-limiting/WAF: no Azure-level rate limiting/WAF rules configured. Use Application Gateway WAF or Front Door for global rate-limiting and DDoS protection.

---

## Recommended Action Items
1. Add PrivateEndpoint resources + Private DNS zones for Postgres and Redis.
2. Provision Azure Monitor diagnosticSettings and Log Analytics workspace; create alert rules for failover, high latency, and persistence errors.
3. Add Key Vault integration for secrets and certificate storage; use managed identities.
4. Deploy PgBouncer in AKS with max client connections = 100; ensure TLS between app and PgBouncer.
5. Test geo-restore procedures and consider read-replicas/cross-region replica strategies for automated failover.


