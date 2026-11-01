# Training plan — MVP go-live

PRD Bab 12.1 ends the MVP with training. The sessions below prepare each audience for UAT and go-live. Each one is a
short demonstration followed by hands-on exercises on the training project. The exercises are the
[UAT scenarios](../uat/scenarios.md), so trained users can run UAT straight away.

## Setup

- **Environment.** Staging, with a **training project** copied from the UAT project. Never use real client data.
- **Accounts.**
  - one per participant, invited before the session;
  - external participants set up their authenticator app beforehand (5 minutes, instructions in the invitation
    e-mail).
- **Material.** The guide for the audience ([README](README.md)). Share it a day before.
- **Trainers.**
  - the product owner for the workflow;
  - a project manager for practice tips;
  - IT for the administrator session.

## Sessions

| # | Audience | Length | Guide | Exercises (UAT IDs) |
|---|----------|--------|-------|---------------------|
| 1 | Partners and project managers | 2 h | [Consultant team](consultant-team.md) | P1–P3, R1–R2, R4, F3–F4, Q2, RP1–RP2 |
| 2 | Analysts | 2 h | [Consultant team](consultant-team.md) | D1–D5, F1–F2, Q1, R4 |
| 3 | Target company representatives | 1 h, can be remote | [Target company](target.md) | R2–R3, Q3 |
| 4 | Client representatives | 45 min, can be remote | [Client](client.md) | F4, Q4, RP2 |
| 5 | IT administrators | 1.5 h | [Administrator](administrator.md) | A1–A6 |

Sessions 1 and 2 can be combined for small teams. Sessions 3 and 4 are run per engagement, usually at kick-off, by
the project manager.

### Session 1 — Partners and project managers

1. **Concepts (15 min).**
   - the project as a security boundary;
   - roles and information barriers: what the target and client never see;
   - watermarks and the audit trail.
2. **Setting up an engagement (25 min).** Create a project from the template, add the team with workstreams, set
   folder access and download policies.
3. **Request list (25 min).** Import from Excel, release a wave, review a delivery: close and reject with a reason,
   internal notes.
4. **Findings review and release (25 min).** Four eyes, return to draft, release; what the client sees.
5. **Q&A approval and reports (20 min).** Approve, return, close; generate, review and issue a report.
6. **Questions (10 min).**

### Session 2 — Analysts

1. **Concepts (10 min).** As in session 1, with workstream-limited access.
2. **Data room (30 min).** Folder upload, scan statuses, versions, viewer, OCR search with phrases and exclusions.
3. **Findings (40 min).**
   - recording from a document page;
   - rating against the risk matrix;
   - citing sources;
   - submitting;
   - working through review notes.
4. **Questions to the target (20 min).** Asking from a document or request item, follow-ups.
5. **Questions (20 min).**

### Session 3 — Target company

1. Signing in with the authenticator app and accepting the NDA.
2. Reading the request list and its reminders.
3. Delivering documents: upload or pick, partial and complete delivery, rejected items.
4. Answering questions with documents.
5. What is confidential and how files are checked.

### Session 4 — Client

1. Signing in, the dashboard.
2. Released findings and the heat map.
3. Asking questions and when answers become visible.
4. Downloading issued reports, and what the watermark means.

### Session 5 — IT administrators

1. Roles versus project membership.
2. Organizations, invitations, disabling users, SSO users.
3. Templates and the risk matrix.
4. The audit log: typical checks.
5. Operations: hand-over of the [runbooks](../runbooks/README.md), backups and restore tests, incident contacts.

## After go-live

- **Support.** One named contact per engagement (the project manager) for target and client users; IT for access
  problems.
- **Feedback.** A short survey after the first engagement on the platform. It feeds the KPI "User satisfaction ≥ 4.0"
  ([PRD](../prd/README.md#goals-and-kpis-bab-2)).
- **Refreshers.** When phase 2 features arrive (portfolio dashboard, retention, tiered report approval), update the
  guides and run a 30-minute refresher for the affected roles.
