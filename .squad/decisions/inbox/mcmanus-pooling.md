Title: HikariCP + Jedis pooling decisions

Context
- Preparing production deployment on Azure using managed PostgreSQL and Azure Redis.

Decision
- Use HikariCP for PostgreSQL with: minimumIdle=5, maximumPoolSize=20, idleTimeout=600s, connectionTimeout=30s.
- Use Jedis client with pool settings: max-active=20, max-idle=10, min-idle=5, max-wait=500ms; set connection timeout=2s and expose max-retries=3 via app.redis.max-retries.

Rationale
- HikariCP is the default high-performance connection pool for Spring Boot; chosen sizes balance concurrency and resource limits for a medium-sized service. minimumIdle=5 ensures warm connections while maximumPoolSize=20 limits DB connections to a predictable cap to avoid exhausting DB connection quotas on Azure.
- Jedis pool mirrors DB sizing to limit outbound connections to Redis and reduce latency from connection churn. A 2s timeout avoids long blocking behavior; 3 retries is conservative for transient network glitches but keeps overall request latency bounded.

Operational notes
- Azure managed Postgres often enforces SSL — the config exposes DB_SSL and DB_SSLMODE env vars. Ensure the JDBC URL includes sslmode=require or provide CA bundle as needed.
- Azure Redis uses TLS — spring.redis.ssl is enabled by default in the prod profile and REDIS_PASS binds to the managed instance password.
- Monitor pool usage (Hikari metrics and Redis client metrics) after rollout; adjust max sizes based on observed QPS and DB connection quotas.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>