# Guide for the consultant team

For partners, project managers (PM), analysts and observers of the consulting firm. Read
[Getting started](README.md#getting-started-everyone) first.

## Who does what

| Task | Partner | Project manager | Analyst | Observer |
|------|:-------:|:---------------:|:-------:|:--------:|
| Create projects, manage the team and folders | ✓ | ✓ | | |
| Release request items to the target, close or reject deliveries | ✓ | ✓ | close/reject | |
| Upload and review documents | ✓ | ✓ | ✓ (own workstreams) | view |
| Record findings and cite sources | ✓ | ✓ | ✓ | |
| Review (approve) findings | ✓ | ✓ | | |
| Release findings to the client | ✓ | | | |
| Approve questions before they go to the target | ✓ | ✓ | | |
| Generate reports | ✓ | ✓ | | |
| Issue reports to the client | ✓ | | | |

An analyst assigned to one workstream (for example Legal) sees only that workstream's folders, request items and
findings.

## 1. Set up a project (partner or PM)

1. **Projects → New project**. Enter:
   - the **Project code**, for example `DD-2026-001`;
   - the name;
   - the **Client (buy-side)** and **Target company (sell-side)** organizations;
   - the period.
2. Choose a **Template**. It creates the data-room folders, the request list and the finding categories for the
   engagement. Without a template, choose the **Workstreams** yourself.
3. Open the project. Under **Team → Add member**:
   - add each person with their **Project role**;
   - optionally limit them to one **Workstream**;
   - for external users, optionally set an **Access until** date.

   Client and target users can only be added from the project's own client and target organizations.
4. Under **Settings**, update the stage as the engagement progresses. **Archive project** at the end: the project
   becomes read-only and external users lose access.

The **Dashboard** tab shows:

- the share of the request list fulfilled;
- items due within 7 days and overdue items;
- findings by risk level;
- progress per workstream.

## 2. Request list

1. **Request list → open the project.** Items from the template are already there.
   - Add items with **New item**, or **Import Excel**. Download the template first; the import is checked before
     anything is saved, and either all rows are added or none.
   - Each item has a workstream, a priority, a due date, a **Wave** and optionally a person in charge.
2. **Release wave.** Choose the wave (and optionally a workstream). Released items become visible to the target,
   who is notified. Items that are not released yet stay internal.
3. When the target delivers, the item moves to **Partially received** or **Received** and you are notified.
   - Open the item to see the attached documents.
   - **Closed** accepts the delivery.
   - **Reject** asks for more. Give a reason; the target sees it and the item is open again.
4. **Comments.** A comment is visible to the target. Tick **Internal** for a note only the team sees.
5. **Export** produces Excel or PDF of the list, for example for a steering meeting.

Reminders go to the target automatically for items due soon or overdue, at most once a day. Project managers also
receive a daily overdue summary.

## 3. Data room

- **Folders.** Create folders and subfolders, rename or delete empty ones.
  - Under **Access**, choose what the target side may do in a folder (**View** or **Upload**), for all target members
    or for individual users.
  - Clients have no data room.
- **Download and print.** **Download allowed** and **Print allowed** are off by default. Partners and project
  managers can always download.
- **Upload.** Drag files, or turn on **Upload folder** and drop a whole folder; subfolders are created.
  - Every file is scanned for malware and its type checked. It shows as **Scanning** until it is **Clean**, and only
    then can it be opened.
  - Uploading a file with the same name adds a new version. **Versions** lists them all.
- **Viewer.** Click a document to open it. Each page is watermarked for you. From a page you can:
  - **Finding from page N**: records a finding with this document and page as its source;
  - **Ask a question**: asks the target about this document.
- **Search documents** finds names and contents, including scanned documents (OCR). Use `"quotes"` for phrases and
  `-word` to exclude a word. Results only include folders you may see.

## 4. Findings

1. **Findings → New finding**, or **Finding from page N** in the viewer. Enter:
   - the workstream, category, title and description;
   - the impact, monetary impact and recommendation;
   - **Impact** and **Likelihood**, each 1 (very low) to 5 (very high).

   The risk level follows the firm's risk matrix. A high or critical rating notifies the project managers, and for
   critical also the partners.
2. Open the finding and **Cite a source document**: choose the folder, the document and the page or section.
   Findings with sources are what the report and the KPI "findings linked to source documents" count.
3. **Submit for review.** This needs a rating and at least one source.
4. **Review (partner or PM).**
   - **Approve**: you cannot approve a finding you wrote yourself (four eyes).
   - **Return to draft**: give a reason; the author is notified.
   - **Review notes** are internal and never shown to the client.
5. **Release to client (partner).** The client is notified and now sees the finding, without review notes or
   history.
   - A released finding cannot be edited.
   - A partner may **Archive** it, with a reason.

The heat map at the top shows open findings by impact × likelihood.

## 5. Q&A

1. **Q&A → Ask a question**, or **Ask a question** from a request item, a document or a finding. Write the question;
   optionally choose the workstream, a specific recipient and a due date.
   - **Save draft** keeps it to yourself.
   - **Submit for approval** sends it to the PM or partner.
   - Questions from the client also arrive as **Awaiting approval**.
2. **Approval (PM or partner).**
   - **Approve and send** notifies the target.
   - **Return to author** sends it back with feedback.

   The target never sees drafts or questions awaiting approval.
3. When the target answers, the question is **Answered** and you are notified. You can then:
   - **Close** it, accepting the answer. If a client asked it, the client can now read the answer.
   - **Ask a follow-up**, which sends it back to the target with your message.
4. **Export** the log to Excel or PDF. Search and filters narrow it down first.

## 6. Reports

1. **Reports → Generate report** (PM or partner). Give a title and the language (Indonesian or English).

   The report contains:
   - a cover with the project and target company;
   - an executive summary;
   - the 5 × 5 heat map;
   - the released findings per workstream, with their sources.

   Each generation is a new version, in Word and PDF.
2. Download Word or PDF to review. Delete a draft version you do not need.
3. **Issue (partner).** The version is final: it can no longer be changed or deleted. The client is notified and
   downloads a PDF watermarked with their own name.

## Good practice

- Cite a source for every finding before submitting it. The client and the report rely on it.
- Write review notes for the team and descriptions for the client. Review notes are never released.
- Keep questions specific and link them to the request item or document they concern.
- Never download documents to personal devices. If you must work offline, use the watermarked PDF.
