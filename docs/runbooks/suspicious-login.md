# Suspicious logins and access attempts

**Alerts:** `DiligenceLoginFailureSpike`, `DiligenceAccountLockouts`, `DiligenceProjectAccessBlocked`,
`DiligenceForbiddenSpike`. PRD Bab 7 (alerts for suspicious login attempts) and Bab 10.1 (anomalous login
detection, IP restriction per project).

## Check

1. **Keycloak events:** admin console → realm *diligence* → *Events* → filter `LOGIN_ERROR`. Group by IP address
   and username. Many users from one IP = password spraying; many IPs against one user = credential stuffing.
2. **Lockouts:** brute-force protection locks a user after 5 failures (realm settings). *Users → <user> →
   Brute force status*.
3. **Blocked project access:** audit log (`/admin/audit`) filter action `project.access-blocked` — shows the
   project, user and IP refused by the project's IP allow-list.
4. **403/404 spike:** audit and application logs by user — many different project or document IDs from one account
   suggests ID probing (IDOR). Row-level security blocks the data; the pattern is still a sign of a compromised or
   malicious account.

## Mitigate

- Block the source IP(s) at Kong / the load balancer (`ip-restriction` plugin) for spraying.
- Disable the targeted or suspicious account and sign out its sessions; ask the user to reset the password and
  re-enrol TOTP.
- For a blocked external user from a legitimate new location, the project manager adds that network to the
  project's IP allow-list after confirming with the client/target.
- If an account accessed documents it should not have: treat as SEV-1 ([incident-response.md](incident-response.md)).
