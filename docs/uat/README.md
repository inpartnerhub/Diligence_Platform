# User acceptance test (UAT) plan — MVP

PRD Bab 12.1 closes the MVP with UAT in Sprint 15–16. Business users confirm that the platform supports a real due
diligence engagement end to end, in each role, before go-live. This plan covers who tests what, where, and when the
MVP counts as accepted. The scenarios are in [scenarios.md](scenarios.md).

## Scope

All MVP requirements marked **M** (and the S items delivered in the MVP) in the
[traceability matrix](../prd/README.md#requirement-traceability). Each scenario names its requirement IDs:

- projects and members;
- request list;
- data room;
- findings;
- Q&A;
- reports;
- administration;
- information barriers.

Out of scope: phase 2 and 3 items (PRJ-05, DOC-08, FND-05, RPT-03, ADM-06, AI features). Load and security are
covered separately ([performance](../../tests/performance/README.md), [penetration test](../security/penetration-test.md)).

## Participants

| Role | Tester profile | Main areas |
|------|----------------|------------|
| Partner | Engagement partner | Approvals, release of findings, report issue |
| Project manager | Engagement manager | Project setup, team, request list, Q&A approval |
| Analyst | Associate per workstream (legal, financial, tax) | Data room review, findings, questions |
| Target | Seller-side representative (from a friendly pilot or a colleague playing the role) | Request list, uploads, answers |
| Client | Buyer-side representative (as above) | Progress, released findings, reports, own questions |
| Super admin | IT administrator | Users, organizations, templates, risk matrix, audit log |
| Observer | Internal audit | Read-only access |

Each tester gets an individual account. External accounts are invited through the platform, so the invitation,
MFA enrolment and NDA acceptance are tested too. Accounts are never shared.

## Environment and data

- **Staging**, deployed from the release candidate commit with production settings (API docs off, ingress client
  addresses, MFA on).
- **Two projects:**
  - a prepared engagement from the standard template, with realistic (anonymised) documents: PDFs, Office files,
    scanned images;
  - a second project used only to confirm that its data stays invisible to the first project's testers.
- **E-mail:** notifications go to a test mailbox, or Mailpit on staging, so testers can confirm reminders and
  notifications.
- **Automated runs:** the end-to-end suite (`tests/e2e`) runs against staging before UAT starts. A red run blocks
  UAT.

## Entry criteria

1. CI is green on the release candidate (backend, frontend, security, infrastructure). The Performance workflow
   baseline passes on staging.
2. No open Critical or High findings from the internal pre-test
   ([penetration-test.md](../security/penetration-test.md)).
3. The end-to-end suite passes on staging.
4. Test accounts and both projects exist, and the testers have received the user guides.

## Execution

- Testers work through their role's scenarios in [scenarios.md](scenarios.md) in order. Each records **Pass**,
  **Fail** or **Blocked**, with a note.
- Cross-role scenarios (marked ⇄) are run together in one session: for example, the target delivers while the
  analyst reviews.
- Defects go to the issue tracker with the scenario ID, the steps, the expected and actual result, and a
  screenshot. Never attach confidential documents.

| Severity | Meaning | Example | Release impact |
|----------|---------|---------|----------------|
| Critical | Data leak, broken information barrier, data loss, no workaround | Target sees a finding | Blocks go-live |
| High | Main workflow blocked, workaround costly | Findings cannot be released | Blocks go-live |
| Medium | Workflow works with a reasonable workaround | Filter resets after export | Fix or accept with a date |
| Low | Cosmetic, wording | Truncated label | Backlog |

## Exit criteria (acceptance)

- Every scenario has been executed. At least 95% pass, and every scenario marked **Must** passes.
- No open Critical or High defects. Medium defects have an owner and a target date, accepted by the product owner.
- The information barrier scenarios (UAT-B\*) pass without exception.
- The product owner and the engagement partner sign off below.

## Sign-off

| Name | Role | Decision | Date | Signature |
|------|------|----------|------|-----------|
| | Product owner | Accepted / Not accepted | | |
| | Engagement partner | Accepted / Not accepted | | |
| | IT / security officer | Accepted / Not accepted | | |
