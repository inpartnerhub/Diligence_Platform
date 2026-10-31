# UAT scenarios — MVP

How to use this page:

- Run the scenarios for your role in order and record **Pass / Fail / Blocked** in the Result column.
- **Must** scenarios have to pass for acceptance ([plan](README.md)).
- ⇄ marks a scenario run together with another role.
- **Auto** names the end-to-end spec that already checks the scenario on every run (`tests/e2e/specs`). UAT still
  confirms it with real users and data.

Project codes in the steps refer to the prepared UAT engagement (called *Project A*) and the second project
(*Project B*).

## Access and administration

| ID | Must | Role | Req. | Steps | Expected | Auto | Result |
|----|------|------|------|-------|----------|------|--------|
| UAT-A1 | ✓ | Super admin | ADM-01 | Invite an external user of the target organization with role Target. | Invitation e-mail arrives. The user sets a password, enrols OTP and accepts the NDA on first login. The user appears as Active. | | |
| UAT-A2 | ✓ | Target, Client | ADM-02 | Sign in; try a wrong password six times on a test account. | OTP is required after the password. The account is locked after five failures and unlocks after the wait period. | `auth.setup` (OTP) | |
| UAT-A3 | ✓ | Any | ADM-02 | Stay idle for 30 minutes, then click. | The session has expired and the user is sent to sign in, returning to the same page afterwards. | | |
| UAT-A4 | | Super admin | PRJ-03 | Duplicate the standard template, rename a folder and a request item, save. | The new template is available when creating a project. | | |
| UAT-A5 | ✓ | Super admin | FND-03 | Change the HIGH threshold in Administration → Risk matrix. | Open findings are re-rated; released findings keep their level. | | |
| UAT-A6 | ✓ | Super admin | ADM-03, ADM-04 | Filter the audit log by a tester and by "document.downloaded". | Every view, download, release and issue from the session is listed with user, time and IP address. | | |

## Projects and team

| ID | Must | Role | Req. | Steps | Expected | Auto | Result |
|----|------|------|------|-------|----------|------|--------|
| UAT-P1 | ✓ | Project manager | PRJ-01, PRJ-03 | Create a project from the standard template with client and target organizations and three workstreams. | Folders and request items from the template exist; the dashboard shows 0% fulfilled. | | |
| UAT-P2 | ✓ | Project manager | PRJ-02 | Add an analyst for one workstream only, a target user with an access expiry, and a client user. | Each member sees the project; the analyst sees only their workstream's folders and items. | | |
| UAT-P3 | ✓ | Project manager | PRJ-04 | Open the project dashboard after the request and findings scenarios. | Completion, overdue items and findings per risk level match the lists. | | |
| UAT-P4 | ✓ | Partner | PRJ-01 | Archive Project A at the end of UAT (last step). | The project becomes read-only; target and client users lose access immediately. | | |

## Request list

| ID | Must | Role | Req. | Steps | Expected | Auto | Result |
|----|------|------|------|-------|----------|------|--------|
| UAT-R1 | ✓ | Project manager | REQ-01 | Import additional items from the Excel template (dry run, then import). | The dry run reports problems per row; the import adds all items or none. | | |
| UAT-R2 | ✓ | Project manager ⇄ Target | REQ-02, REQ-06 | Before release, the target opens the request list. Then release wave 1. | Before: the target sees nothing. After: the target sees wave 1 items as Requested and is notified. | `mvp-flow` 1 | |
| UAT-R3 | ✓ | Target | REQ-03 | On an item, upload a file into a permitted folder (and pick an existing one on another item), then report "Completely delivered". | The documents are attached; status is Received; the team is notified. Folders without upload permission cannot be chosen for upload. | `mvp-flow` 2 | |
| UAT-R4 | ✓ | Analyst ⇄ Target | REQ-02, REQ-03 | Reject a delivery with a reason; add an internal note and a public comment. | The target sees the item open again with the reason and the public comment, never the internal note. | | |
| UAT-R5 | | Target | REQ-04 | Leave an item due tomorrow open overnight. | One reminder e-mail and in-app notification per day; managers get the overdue digest. | | |
| UAT-R6 | | Project manager | REQ-05 | Export the request list to Excel and PDF. | Both files contain the same columns as the screen; the export is in the audit log. | | |

## Data room

| ID | Must | Role | Req. | Steps | Expected | Auto | Result |
|----|------|------|------|-------|----------|------|--------|
| UAT-D1 | ✓ | Analyst | DOC-02 | Upload a folder with PDFs, Word, Excel and a scanned image; upload the EICAR test file on staging. | Files appear as Pending, then Clean. EICAR is marked Malware and cannot be opened. An .exe is rejected. | | |
| UAT-D2 | ✓ | Analyst | DOC-03, DOC-04 | Open a Word document and a scanned PDF in the viewer. | Pages show within 3 seconds; each page carries your name, e-mail, IP address and time. | | |
| UAT-D3 | ✓ | Target | DOC-05 | Try to download from a folder where downloads are not allowed; then from one where they are. | Not allowed: no download button. Allowed: a watermarked PDF without print permission unless printing is allowed. | | |
| UAT-D4 | ✓ | Analyst | DOC-06 | Search for a word that only appears in the scanned image. | The document is found, with the hit highlighted. | | |
| UAT-D5 | | Analyst | DOC-07 | Upload a new version of an existing file. | Version 2 is current; version 1 is still listed and viewable. | | |

## Findings

| ID | Must | Role | Req. | Steps | Expected | Auto | Result |
|----|------|------|------|-------|----------|------|--------|
| UAT-F1 | ✓ | Analyst | FND-01, FND-02, FND-03 | Record a finding from page 3 of a document in the viewer; rate impact and likelihood. | The source and page are pre-filled; the risk level follows the matrix; HIGH/CRITICAL notifies managers. | `mvp-flow` 3 | |
| UAT-F2 | ✓ | Analyst, Project manager | FND-04 | The analyst submits the finding for review. A project manager records and submits a finding of their own, then tries to approve it. | Status In review. Analysts never see an approve action; a reviewer cannot approve their own finding (four eyes). | `mvp-flow` 3 | |
| UAT-F3 | ✓ | Project manager ⇄ Client | FND-04 | Approve the finding; the client opens Findings. | Approved; the client still sees nothing. | `mvp-flow` 4 | |
| UAT-F4 | ✓ | Partner ⇄ Client | FND-04 | Release the finding; the client opens Findings. | Released; the client sees the finding with its rating, without review notes or history. | `mvp-flow` 5–6 | |
| UAT-F5 | ✓ | Analyst | FND-04 | Try to edit the released finding. | Not possible; only a partner can archive it, with a reason. | | |

## Q&A

| ID | Must | Role | Req. | Steps | Expected | Auto | Result |
|----|------|------|------|-------|----------|------|--------|
| UAT-Q1 | ✓ | Analyst ⇄ Target | QNA-01, QNA-02 | Ask a question from a request item, submit it for approval; the target opens Q&A. | Awaiting approval; the target does not see it. | `mvp-flow` 7 | |
| UAT-Q2 | ✓ | Project manager | QNA-02 | Return a question to its author with feedback; approve and send another. | The author sees the feedback; the sent question reaches the target with a notification. | `mvp-flow` 8 (send) | |
| UAT-Q3 | ✓ | Target | QNA-01 | Answer and upload a supporting document from the answer panel. | Answered; the document is in the chosen folder and attached to the answer; the team is notified. | `mvp-flow` 9 | |
| UAT-Q4 | ✓ | Project manager ⇄ Client | QNA-02 | The client asks a question; the team answers through the target and closes it. | The client sees the answer only once the question is closed. | `mvp-flow` 10 (close) | |
| UAT-Q5 | | Project manager | QNA-03 | Search the log and export it to Excel and PDF. | The export matches what the user can see. | | |

## Reports

| ID | Must | Role | Req. | Steps | Expected | Auto | Result |
|----|------|------|------|-------|----------|------|--------|
| UAT-RP1 | ✓ | Project manager | RPT-01, RPT-02 | Generate a report in Indonesian and one in English. | Word and PDF: cover with project and target company, executive summary, 5 × 5 heat map, findings per workstream with sources. Only released findings. | `mvp-flow` 11 | |
| UAT-RP2 | ✓ | Partner ⇄ Client | RPT-01 | Issue a version; the client downloads it. | The issued version can no longer be changed or deleted; the client gets a PDF watermarked with their identity; the download is audited. | `mvp-flow` 11–12 | |

## Information barriers

Run these with two browsers side by side. A failure here is always Critical.

| ID | Must | Role | Req. | Steps | Expected | Auto | Result |
|----|------|------|------|-------|----------|------|--------|
| UAT-B1 | ✓ | Target | Bab 3 | Look for findings and reports in the menu; open their URLs directly. | Not in the menu; no findings or reports are shown. | `barriers` | |
| UAT-B2 | ✓ | Client | Bab 3 | Look for the data room and request list; open their URLs directly. | Not in the menu; no folders or items are shown. | `barriers` | |
| UAT-B3 | ✓ | Any member of Project A | PRD 8.1 | Paste a URL of a Project B document, finding or question. | Not found; nothing of Project B is shown. | (backend `CrossProjectAccessIntegrationTest`) | |
| UAT-B4 | ✓ | Target | DOC-01 | With a target account limited to one folder, browse and search the data room. | Only the granted folder's documents appear, in lists and search results. | | |
| UAT-B5 | ✓ | Analyst (one workstream) | PRJ-02 | Browse folders and request items. | Only the assigned workstream's folders and items. | | |
