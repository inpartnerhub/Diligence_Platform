# Slow API / database pool exhausted

**Alerts:** `DiligenceSlowApi` (P95 > 2 s, PRD Bab 7), `DiligenceDatabasePoolExhausted`.

## Check

1. Which endpoints: Grafana → *http_server_requests* by `uri`, or
   `topk(5, histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m]))))`.
2. Database: `hikaricp_connections_active` vs. pool size (`DB_POOL_SIZE`, default 20); slow queries in
   `pg_stat_statements` / RDS Performance Insights.
3. Traces: open a slow request in the tracing backend (OpenTelemetry) to see whether time goes to SQL, OpenSearch,
   S3 or a remote call.
4. Load: HPA status `kubectl -n diligence get hpa`.

## Mitigate

- Scale out: raise HPA `maxReplicas`; the backend is stateless.
- Pool exhausted: look for code that holds a transaction during remote calls. Webhook deliveries deliberately run
  outside transactions (ADM-07); a regression there shows up as pending connections while receivers are slow.
- Search slow: check OpenSearch cluster health and JVM pressure; scale the domain.
- Never raise `DB_POOL_SIZE` above what RDS `max_connections` allows for all replicas together.
