# ADR-0002: Secrets delivered via Vault Secrets Operator as environment variables

- **Status:** Accepted
- **Date:** 2026-10-30
- **Deciders:** Tech Lead / Architect, Security Officer

## Context

The backend needs credentials for PostgreSQL, Redis, RabbitMQ and OpenSearch. Secrets must never be committed
and should be rotatable without code changes.

## Decision

HashiCorp Vault is the source of truth. The **Vault Secrets Operator** syncs KV v2 secrets into Kubernetes
Secrets, consumed by the application as environment variables, and restarts the deployment on change.
Terraform writes generated infrastructure credentials to `kv/diligence/<env>/backend/infrastructure`;
operators manage `kv/diligence/<env>/backend/application`. The application has **no Vault client dependency**.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Spring Cloud Vault in the app | Dynamic secrets in-process | Couples app to Vault and its auth; harder local dev |
| Vault Agent sidecar injector | Files, templating | Sidecar per pod; more resources |
| Plain Kubernetes Secrets in Git (sealed/SOPS) | Simple GitOps | Rotation is manual; secrets material in repo |

## Consequences

- Same configuration mechanism (env vars) in every environment, including local.
- Next step: Vault database secrets engine with `VaultDynamicSecret` for short-lived DB credentials.
