# Production Keycloak (ADR-0009, ADR-0011)

Self-hosted Keycloak 26.8 on EKS in its own `restricted` Pod Security namespace (`diligence-keycloak-<env>`),
backed by the environment's RDS PostgreSQL instance.

## Prerequisites & Secrets

Create the operator-managed secret `diligence/<env>/keycloak` in AWS Secrets Manager (Terraform grants External
Secrets Operator read access to `diligence/<env>/keycloak-*`). Required keys:

- `KC_DB_URL`: `jdbc:postgresql://<rds-endpoint>:5432/keycloak?sslmode=verify-full`
- `KC_DB_USERNAME`: dedicated PostgreSQL role for Keycloak (separate from the application's database and schema)
- `KC_DB_PASSWORD`: password for `KC_DB_USERNAME`
- `KC_BOOTSTRAP_ADMIN_USERNAME`: initial `master` realm admin username
- `KC_BOOTSTRAP_ADMIN_PASSWORD`: initial `master` realm admin password

## Deploy

```bash
kubectl apply -k infrastructure/kubernetes/keycloak/overlays/<env>
kubectl -n diligence-keycloak-<env> rollout status deploy/keycloak
```

Wait for `ExternalSecret/keycloak-secrets` to sync before the pod starts.

## Admin Access (Internal Only)

The public Ingress exposes only `/realms/diligence`, `/resources`, and `/js`. `/admin` and `/realms/master` are never
exposed publicly; use `kubectl port-forward` for administration:

```bash
kubectl -n diligence-keycloak-<env> port-forward svc/keycloak 8080:8080
# Open http://localhost:8080/admin
```

## Scaling to HA (`>= 2` replicas)

At launch (`ADR-0011`), Keycloak runs with `replicas: 1`. When scaling to `>= 2` replicas across availability zones:

1. Enable Keycloak distributed caching (`KC_CACHE=ispn`, `KC_CACHE_STACK=kubernetes` or `jdbc-ping`, plus headless
   Service / DNS ping or `JGROUPS_DISCOVERY_PROPERTIES` as required by the chosen Infinispan discovery mechanism, and
   matching `NetworkPolicy` ingress on Infinispan ports `7800`/`57800` between Keycloak pods).
2. Add `topologySpreadConstraints` across zones and a `PodDisruptionBudget` (`minAvailable: 1`).
3. Ensure `KC_DB_URL` points at a Multi-AZ RDS instance (`high_availability = true` in Terraform).
