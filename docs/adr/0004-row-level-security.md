# ADR-0004: PostgreSQL row-level security for project isolation and information barriers

- **Status:** Accepted
- **Date:** 2026-10-30
- **Deciders:** Tech Lead / Architect, Security Officer

## Context

PRD 8.1 makes every project a security boundary ("every query must be filtered by project ID and user rights").
PRD Bab 3 requires information barriers enforced at the data level, not just the UI: the target never sees
findings; the client never sees internal correspondence. Broken access control (IDOR) is the top risk (Bab 15).

## Decision

1. Every project-scoped table carries `project_id` (denormalized where the PRD relates it through a parent).
2. RLS policies (`V5__row_level_security.sql`) grant access through `prj.project_member` using the per-project role,
   and encode the barriers (finding status `RELEASED` for clients, question status for targets, report `ISSUED`).
3. The application connects as the schema owner but every transaction starts with `SET LOCAL ROLE diligence_app`
   (a non-owner, non-superuser role) and `set_config('app.user_id' | 'app.role', …, true)`, done by
   `RowLevelSecurityTransactionManager`. Superusers bypass RLS; members of a NOLOGIN role do not.
4. Work without a user (message listeners, schedulers) runs with `app.role = 'system'`, which policies trust.
5. Application-level checks (`@PreAuthorize`, ABAC) remain the primary authorization; RLS is the safety net.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Application filtering only (Hibernate filters / specifications) | Simple, portable | One missed filter leaks a project; no defence in depth |
| Schema or database per project | Strong isolation | Thousands of schemas, migrations per project, cross-project dashboards hard |
| Separate login user for the app instead of `SET ROLE` | No per-transaction statement | Two credentials to manage; Flyway and app split |

## Consequences

- All project data access must happen inside a transaction (the context is transaction-local).
- Each new project-scoped table needs its policies in the same migration, plus an integration test for barriers.
- `system` work must never be triggered directly by a user request without re-checking authorization.
- Small per-transaction overhead (two statements); policies use indexed `project_member (user_id, project_id)`.
