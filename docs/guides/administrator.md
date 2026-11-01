# Guide for administrators

For the IT administrators (role **Super Admin**). Administration is under **Administration** in the menu. Your
account always uses two-factor authentication. Operational topics (deployments, backups, secrets) are in the
[runbooks](../runbooks/README.md), and security controls in [SECURITY.md](../../SECURITY.md).

## Roles

| Global role | Who | Notes |
|-------------|-----|-------|
| Super Admin | IT administrators | Administration and audit log. Never a project member by default. |
| Partner / Director | Engagement partners | Creates projects; releases findings; issues reports |
| Project Manager | Engagement managers | Creates projects; runs request list and Q&A |
| Analyst / Reviewer | Associates | Works in the projects they are added to |
| Client (buy-side) | Buyer's representatives | External: MFA and NDA |
| Target (sell-side) | Seller's representatives | External: MFA and NDA |
| Observer / Auditor | Internal or external auditors | Read-only; MFA and NDA |

The global role decides what a user may do in general. Access to a project comes from project membership, which
the project's partner or manager grants (**Team** tab), optionally per workstream and with an expiry.

## Organizations

**Administration → Organizations → New organization.** Choose the type:

- **Consultant**: the firm;
- **Client**: buy side;
- **Target**: sell side.

Projects link one client and one target organization, and external users can only join a project from those two.
Deactivate organizations that are no longer engaged.

## Users

- **Invite user.** Enter the e-mail, name, organization, role and language.
  - The user receives an e-mail to set a password.
  - Roles that require it also set up two-factor authentication.
  - External roles accept the NDA.
  - The user shows as **Invited** until their first sign-in, then **Active**.
- **Change role.** This takes effect within a few minutes, when the user's session next refreshes.
- **Disable.** The user is signed out of new sessions immediately. Prefer it to deletion: the audit trail keeps
  their name. **Enable** restores access.
- Internal users who sign in through the company's Microsoft or Google account (SSO) are created automatically at
  their first sign-in, with the least-privileged role. Assign their role afterwards.
- A user who lost their phone: reset the authenticator in Keycloak (realm *diligence* → Users → Credentials) after
  verifying their identity out of band.

## Groups

**Groups** collect users, for example a client's deal team or an internal practice group. **Edit members** to change
who belongs.

In the data room, the folder **Access** dialog grants to the whole target side or to individual users. Grants per
group are supported by the API but not yet offered in that dialog.

## Project templates

**Project templates** define what a new project starts with. Duplicate the standard template to adapt it, rather
than editing it.

| Field | Format |
|-------|--------|
| Data room folders | One folder per line |
| Request list items | One per line: `WORKSTREAM \| category \| description` |
| Finding categories | One per line: `WORKSTREAM \| category` |

Workstreams are `FINANCIAL`, `TAX`, `LEGAL`, `COMMERCIAL`, `OPERATIONAL`, `HR`, `IT_CYBER` and `ESG_COMPLIANCE`.
A line that does not follow the format is reported by its number.

## Risk matrix

**Risk matrix** sets the score thresholds (impact × likelihood, 1–25) for **Medium**, **High** and **Critical**.
They must increase.

**Save thresholds** re-rates every open finding and reports how many changed. Released findings keep the level they
were released with. Agree changes with the partners first: they affect ratings in all projects.

## Audit log

**Audit log** lists every security-relevant action: who, when, from which IP address, on what, and the outcome.
This includes:

- sign-ins;
- document views and downloads;
- releases and issues;
- exports;
- membership and role changes.

Filter by action or object type. The log cannot be changed or deleted, not even by administrators.

Typical checks:

- who downloaded a document: filter by `document.downloaded`;
- what a user did before being disabled;
- a periodic review of external access.

## Checklist for a new engagement

1. The client and target organizations exist.
2. The partner, project managers and analysts have accounts with the right global roles.
3. External users are invited by the project manager's request, with their organization set.
4. After the engagement, the project is archived. External access ends automatically, and accounts no longer needed
   are disabled.
