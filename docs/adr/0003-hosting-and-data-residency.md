# ADR-0003: Hosting model and data residency

- **Status:** Proposed — recommendation below, awaiting the steering committee and Legal (PRD 15.2, #69)
- **Date:** 2026-10-30, recommendation added 2026-11-13
- **Deciders:** Steering committee (Partner, Product Owner, Tech Lead), Security Officer, Legal

## Context

PRD Bab 7 and 15 require alignment with UU No. 27/2022 (PDP) and ask whether clients require data to stay in
Indonesia, and whether the platform runs on public cloud, private cloud or on-premise. The answer drives the
Terraform target, the object storage product and the backup/DR design.

The platform holds confidential deal documents and personal data of client and target staff (names, e-mail
addresses, IP addresses in the audit log, documents that may contain anything). Several open issues wait for this
decision: immutable backups outside the primary region (#178), the subprocessor and cross-border transfer assessment
(#187), the EKS vs ECS choice (#183) and the production Keycloak hosting (#169).

## Options

| Option | Pros | Cons |
|--------|------|------|
| A. AWS ap-southeast-3 (Jakarta) | Data in Indonesia; managed PostgreSQL, Valkey, MQ, OpenSearch, S3; the current Terraform fits and staging/prod already refuse other regions (#163) | AWS's only region in Indonesia: no in-country second region for DR or backup copies; some services/instance types arrive later in this region; cost |
| B. Other public cloud region in Indonesia (Azure, GCP) | Data in Indonesia; Entra ID affinity (Azure) | Terraform modules rewritten per service; same single-region limits to check per provider |
| C. Private cloud / on-premise Kubernetes | Full control; satisfies strict client clauses | The team operates PostgreSQL, Redis, RabbitMQ, OpenSearch, S3-compatible storage (SeaweedFS, Ceph), backups and DR itself |
| D. Hybrid: A by default + dedicated on-prem deployments for specific clients | Commercial flexibility for clients with strict clauses | Two operating models; release discipline |

## Current state

The repository ships the AWS reference implementation (`infrastructure/terraform`). Following the interim rule below,
the environment examples and the backend's `S3_REGION` use `ap-southeast-3`, and the staging and prod root modules
refuse any other region (#163). Everything above the infrastructure layer is cloud-neutral: containers, Kubernetes,
S3-compatible storage (SeaweedFS locally; Ceph or similar on-prem), OpenSearch, RabbitMQ, Keycloak, Vault.

Data that already leaves the primary environment, or may, by design:

| Flow | What | Where it goes |
|------|------|---------------|
| Error tracking (Sentry, optional) | Stack traces, request paths; `send-default-pii: false` | The Sentry organization's region |
| E-mail (SMTP relay, e.g. Amazon SES) | Recipient address, invitation and notification text | The relay's region |
| SSO (Microsoft Entra ID / Google, optional) | Sign-in of internal users | The identity provider |
| Webhooks (ADM-07) | Event payloads configured by the Super Admin | Receivers' own hosting |

## Recommendation

**Option A, AWS ap-southeast-3, for every environment that holds real client data**, with Option D kept open
commercially (the cloud-neutral layers allow a dedicated on-prem deployment later without changing the application).

1. **Primary data stays in Indonesia**: database, object storage, search index, queues, caches, backups and logs in
   ap-southeast-3 (already enforced for staging and prod, #163).
2. **Backups (#178)**: a locked AWS Backup vault in a **separate AWS account in ap-southeast-3**, not a copy to another
   country. It protects against a compromised production account and deletion; it does not protect against a loss of
   the whole region.
3. **Regional outage**: accepted as a residual risk and written into the DR runbook (RTO ≤ 4 h holds only for failures
   inside the region). If a client contract requires a second site, the second site is in Indonesia (another
   provider's Jakarta region or on-prem), restored from exported backups, never a foreign AWS region by default.
4. **Cross-border flows** listed above are kept to what is necessary and recorded in the subprocessor list (#187):
   Sentry without personal data or in an in-country/self-hosted setup, SES in ap-southeast-3 if available there
   (otherwise the nearest region, recorded as a transfer), SSO only for the firm's own staff.
5. **Keycloak (#169)** runs in the same region and account boundary as the application.

## Questions for Legal (to settle before *Accepted*)

- Do any target client segments (banks and other OJK-supervised institutions, state-owned enterprises, public
  sector) require their data, or their electronic systems, to be in Indonesia, or need regulator approval otherwise?
- For the flows above that leave Indonesia: is the UU PDP transfer basis (adequate protection in the receiving
  country, appropriate safeguards such as contractual clauses, or consent) in place for each, and what must the
  privacy notice say?
- Is accepting the regional-outage risk (point 3) compatible with the client contracts and the PRD's RPO/RTO?

## Decision

Pending. Until decided: use `ap-southeast-3` for any shared environment that holds real client data, and keep new
infrastructure code behind the existing module interfaces so the target can change.

## Consequences (if the recommendation is accepted)

- #178: AWS Backup plan with Vault Lock into a backup account in ap-southeast-3; the account id becomes a Terraform
  variable.
- #187: the subprocessor list starts from the flow table above.
- #183: EKS stays (the region offers it; no change of orchestrator needed for residency).
- #169: Keycloak deployment designed for ap-southeast-3.
- `infrastructure/terraform` keeps its single-provider modules; SECURITY.md and the backup/DR runbook name the region
  and the residual regional risk.
