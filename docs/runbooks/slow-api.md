# Slow API / database pool exhausted

**Alerts:** `DiligenceSlowApi` (P95 > 2 s, PRD Bab 7), `DiligenceDatabasePoolExhausted`.

## Check

1. Which endpoints: Grafana → *http_server_requests* by `uri`, or
   `topk(5, histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m]))))`.
2. Database: `hikaricp_connections_active` vs. pool size (`DB_POOL_SIZE`, default 10 on single-server / 20 on the
   8 GiB preset); slow queries in `pg_stat_statements` / RDS Performance Insights.
3. Traces: open a slow request in the tracing backend to see whether time goes to SQL (including the full-text search
   query), S3 or a remote call.
4. Load: HPA status `kubectl -n diligence get hpa`.

## Mitigate

- Scale out: raise HPA `maxReplicas`; the backend is stateless.
- Pool exhausted: look for code that holds a transaction during remote calls. Webhook deliveries deliberately run
  outside transactions (ADM-07); a regression there shows up as pending connections while receivers are slow.
- Search slow: full-text search runs in PostgreSQL (`dr.document_search_index`, GIN index on a generated `tsvector`).
  Check the query plan (`EXPLAIN` the `websearch_to_tsquery` query) and database load; if the corpus has outgrown
  `tsvector`, see the OpenSearch scale-up trigger in ADR-0011.
- Never raise `DB_POOL_SIZE` above what PostgreSQL `max_connections` allows for all replicas together.

> **Single-server (compose).** There is no HPA or Grafana: read metrics from Prometheus (`--profile observability`,
> `127.0.0.1:9090`) or `127.0.0.1:8081/actuator/prometheus`, tail logs with
> `docker compose -f compose.prod.yaml logs backend --since=15m`, and "scale out" means moving to the 8 GiB preset
> (ADR-0012), not raising replicas.
