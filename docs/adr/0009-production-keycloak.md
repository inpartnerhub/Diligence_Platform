# ADR-0009: Production Keycloak — self-hosted on the cluster, realm as code per environment

- **Status:** Accepted (2026-11-14); implementation in #169
- **Date:** 2026-11-13, decided 2026-11-14
- **Deciders:** Tech Lead / Architect, Security Officer, cluster owner (ADR-0007)

## Context

Keycloak is the identity provider for every user (PRD ADM-02): password policy and breached-password list (#65),
TOTP for every role (#161), step-up with `amr` containing `otp` (#160), the confidential web client (#175), optional
Entra ID / Google brokering, brute-force protection, and the admin API the backend uses to invite and disable users.

This repository runs Keycloak only for local development (`compose.yaml`, `infrastructure/docker/keycloak/
diligence-realm.json`, `make keycloak-bootstrap`). There is no production deployment, no database for it, and the
realm JSON has only `localhost` redirect URIs, so production HA, TLS, admin-console exposure and redirect URIs cannot
be reviewed (AUD-10). Several merged changes (#160, #161, #175) need realm changes in every environment, applied by
hand today.

The platform is Azure Indonesia Central (ADR-0003), on AKS as proposed in ADR-0007.

## Options

| Option | Pros | Cons |
|--------|------|------|
| A. Keycloak on the AKS cluster (Keycloak Operator or the official image as a Deployment), its own database on the Azure Database for PostgreSQL server, realm as code | Keeps every feature the platform relies on; same region and operating model as the application; realm reviewable in Git | The team operates Keycloak upgrades and its clustering |
| B. Managed Keycloak (a hosted Keycloak provider) | No Keycloak operations | Another subprocessor, possibly outside Indonesia (ADR-0003, #187); less control over extensions and versions |
| C. Replace Keycloak with Microsoft Entra External ID | Fully managed, native to Azure | Re-implements OTP-for-every-role, `amr`-based step-up, the breached-password list, the admin integration and the BFF client: application changes and new risks; tenant data location to verify against ADR-0003 |

## Recommendation

**Option A.**

1. **Deployment**: Keycloak 26.x (the version tested locally) in its own namespace on AKS, at least 2 replicas spread
   over availability zones, distributed cache between them, `KC_HOSTNAME` = the public sign-in host, TLS at the Kong
   ingress, health and metrics on the management port only (the existing `DiligenceKeycloakDown` alert and
   login-failure rules apply).
2. **Database**: a separate database and login on the Azure Database for PostgreSQL Flexible Server (not the
   application's schema), included in its backups and in the backup copies to the AWS site (ADR-0003).
3. **Admin console not public**: the `master` realm and `/admin` are served only on an internal host (VPN or
   `kubectl port-forward`), never through the public ingress; the public route allows `/realms/diligence/*` and the
   resources it needs.
4. **Realm as code**: the `diligence` realm applied by a configuration tool (e.g. keycloak-config-cli) from Git, with
   per-environment values (redirect and post-logout URIs, web origins, SMTP, brokers) in the overlays; client secrets
   and SMTP passwords from Vault. A CI check refuses `localhost` URIs in staging and prod values. The realm changes of
   #160, #161 and #175 become part of this configuration instead of manual steps.
5. **Entra ID brokering** for the firm's own staff stays optional, as today.
6. **Owner**: the cluster owner of ADR-0007 also owns Keycloak upgrades.

## Decision

1. **Keycloak 26.x self-hosted on AKS** in Azure Indonesia Central: its own namespace, at least 2 replicas across
   availability zones, its own database and login on the Azure Database for PostgreSQL server (in its backups and the
   copies to the AWS site), TLS at the Kong ingress, management port for health and metrics only.
2. **Admin console internal only**: the `master` realm and `/admin` never on the public ingress.
3. **Realm as code with keycloak-config-cli**: the `diligence` realm file in this repository plus per-environment
   variables (redirect and post-logout URIs, web origins, SMTP, brokers) in the overlays, applied by a Job on every
   deploy; client secrets and SMTP passwords from Vault (ADR-0010). A CI check refuses `localhost` URIs in staging and
   prod values. The manual realm steps of #160, #161 and #175 move into this configuration.
4. **Owner**: irsalshydiq, the platform owner of ADR-0007, also owns Keycloak upgrades.

## Consequences

- New work, in this order: Keycloak manifests (overlay per environment) and its database; the realm configuration as
  code with the `localhost` check; the internal admin route; a runbook update (`docs/runbooks/keycloak-outage.md`) for
  upgrades and restore.
- #187 does not gain a subprocessor for identity.
- Realm changes in future pull requests are reviewed like any other code and reach every environment the same way.
