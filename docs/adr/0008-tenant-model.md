# ADR-0008: Tenant model — one operating firm, the project as the security boundary

- **Status:** Proposed — awaiting the steering committee (#153)
- **Date:** 2026-11-13
- **Deciders:** Steering committee (Partner, Product Owner, Tech Lead), Security Officer

## Context

The architecture and security validation of 2026-11-12 (AUD-02) found that the requirements it was given treat the
**organization** as the primary security boundary, while the implementation treats the **project** as the boundary.
Both are defensible; the choice was never recorded.

What the code does:

- One deployment serves one operating firm (the consultant). `adm.organization.type ∈ {CONSULTANT, CLIENT, TARGET}`.
- The firm's staff (`PARTNER`, `PROJECT_MANAGER`, `ANALYST`, `SUPER_ADMIN`) are not bound to an organization and can be
  made members of any project.
- A project references one client and one target organization. Access to project data goes through
  `prj.project_member` and the per-project role; every project-scoped table carries `project_id` and is protected by
  row-level security (ADR-0004).
- External users (`CLIENT`, `TARGET`) belong to an organization and see only projects they are members of, within the
  information barriers of PRD Bab 3. `OBSERVER` (internal or external auditor) is read-only and not bound to an
  organization.

## Options

| Option | Meaning | Cost |
|--------|---------|------|
| A. Operating firm + external counterparties (current) | Tenant = the firm operating the deployment; project = security boundary; organizations = counterparties | Nothing to build; the organization rules must be enforced in the database, not only in services |
| B. Multi-tenant SaaS (organizations as tenants) | Several consulting firms share a deployment; every row carries a tenant id; RLS on tenant first | `tenant_id` on every table and policy, tenant-scoped identity realm or claims, tenant-aware search index and object storage, tenant administration; a re-architecture |

## Recommendation

**Option A.** The PRD describes one firm's due diligence platform; nothing requires several firms in one deployment.
A second firm would get its **own deployment** (ADR-0003, option D), which also keeps its data physically separate.

What "tenant" means, and the rules that must hold, each enforced in the database as well as in the services:

| Rule | Enforced by |
|------|-------------|
| Project data is visible only to project members, within the role's barriers | RLS on every project-scoped table (ADR-0004) |
| A `CLIENT`/`TARGET` member belongs to the project's client/target organization | Trigger on `prj.project_member` (#155) |
| External users never read other organizations' people | RLS on `adm.*` (#154) |
| Deactivating an organization ends its members' project access | #156 |
| Data-room rows cannot point into another project | Composite foreign keys (#181) |
| Accounts without an application role see nothing | #152 |
| Cross-organization access is limited to the firm's staff and `OBSERVER` | Membership rules above; `OBSERVER` is read-only |

## Decision

Pending.

## Consequences (if the recommendation is accepted)

- ARCHITECTURE.md §4 and SECURITY.md refer to this ADR as the definition of "tenant".
- New project-scoped tables need `project_id` and policies (as today); new organization-scoped rules need a database
  check, not only a service check.
- Serving another firm means another deployment, not a tenant column.
