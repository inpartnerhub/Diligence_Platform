# Incident response plan

PRD Bab 10.1 ("rencana tanggap insiden"), Bab 10.2 (UU No. 27/2022 PDP) and Bab 14 (Definition of Done). Applies to
security incidents (unauthorised access, data leakage, malware, account takeover) and major outages.

## Severity

| Level | Examples | Response |
|-------|----------|----------|
| **SEV-1** | Confirmed or likely leak of client/target documents or personal data; information barrier broken (target sees findings, client sees internal notes); production down during business hours | Page on-call + Security Officer immediately; Partner informed within 1 h |
| **SEV-2** | Suspected account takeover, malware found after upload, part of the platform down, backups failing | On-call within 30 min, Security Officer same day |
| **SEV-3** | Single user locked out, degraded performance, failed webhook deliveries | Next business day |

## Roles

- **Incident lead** (on-call engineer, later Tech Lead): owns the timeline and decisions.
- **Security Officer**: forensics, evidence, decides whether personal data is affected.
- **Data Protection Officer** (PDP *pejabat pelindungan data pribadi*): regulator and data-subject notifications.
- **Partner / Product Owner**: client and target communication.

## Steps

1. **Detect & record.** Open an entry in the incident register: time, reporter, alert or report, systems involved.
2. **Contain.** Depending on the case:
   - Disable the user in Keycloak (`Users → <user> → Enabled: off`) and revoke sessions (`Sessions → Sign out`).
   - Remove external access to a project: end the members' access period on the team tab, or restrict the project
     to known networks (project settings → *IP allow-list*, PRD 10.1).
   - Turn off a webhook (`/admin/webhooks`) if events go to a wrong or compromised receiver; rotate its key.
   - Lock the data room by archiving the project if documents are being exfiltrated.
3. **Preserve evidence.** Export the audit log for the period (`/admin/audit` → export, or
   `GET /api/v1/admin/audit-logs/export`). The audit log is append-only (ADM-03); do not delete anything. Keep
   Keycloak events, Kong and application logs (OpenSearch) for the window.
4. **Assess.** Use the audit log to answer *who saw or downloaded what, when and from where* (ADM-04: activity per
   user and per document). Watermarks name the viewer, e-mail, IP and time on every page of a leaked copy (DOC-04).
5. **Notify (UU PDP Art. 46).** If personal data was exposed, the DPO notifies the affected data subjects and the
   supervising authority **in writing within 3 × 24 hours** of becoming aware, stating the data concerned, when and
   how it happened, and the remediation. Notify the client under the engagement agreement.
6. **Eradicate & recover.** Fix the cause, rotate affected secrets ([secret-rotation.md](secret-rotation.md)),
   restore data if needed ([backup-restore.md](backup-restore.md)).
7. **Post-mortem** within 5 business days, blameless: timeline, root cause, what detection missed, actions with
   owners. Update the runbooks and alert rules.

## Drills

Run a table-top exercise of a SEV-1 data-leak scenario before go-live and at least yearly, together with the
quarterly restore test.
