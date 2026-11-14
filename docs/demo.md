# Demo mode (branch `demo`)

A role picker at `/demo` signs in as a prepared demo user per role, without a login form, into a demo project with
data at every stage. For presentations on the **local stack only**: it is active only with `DEMO_MODE=true` and a
plain-HTTP identity provider (`OIDC_ALLOW_HTTP=true`, `APP_BASE_URL` on `http://`), and this branch is not merged into
`main`.

## Start

```bash
make up                      # infrastructure
make keycloak-bootstrap      # client secrets, local users
make backend-run             # second terminal
make demo-setup              # demo.* users (generates DEMO_MODE/DEMO_PASSWORD/DEMO_TOTP_SECRET into .env)
make demo-seed               # project DEMO-001 with data (backend must be running)
```

Copy `DEMO_MODE`, `DEMO_PASSWORD` and `DEMO_TOTP_SECRET` from `.env` to `apps/frontend/.env.local`, then
`make frontend-run` and open http://localhost:3000 — without a session it opens the role picker. Inside the app,
"Mode demo · Ganti peran" (top right) goes back to the picker.

`make demo-seed` can be repeated: the previous DEMO-001 is closed and hidden from the demo users, and a fresh one is
created.

## Roles and what the demo project shows

| Role (user) | What to show |
|-------------|--------------|
| Partner (`demo.partner`) | Released findings, the issued report, closing the project |
| Project Manager (`demo.pm`) | Dashboard per workstream, request list with waves (wave 2 not yet released), finding in review, question awaiting approval |
| Analyst (`demo.analyst`) | Data room (PDFs with watermarked preview, full-text search), draft finding, the internal team folder |
| Client (`demo.client`) | Only the two released findings and the issued report; no data room, no request list |
| Target (`demo.target`) | Requested items (one rejected with a reason, one overdue), uploads, an open question to answer; no findings, no reports, no internal folder |
| Observer (`demo.observer`) | Everything, read-only |
| Super Admin (`demo.admin`) | Users, organizations, risk matrix, audit log of everything the seed did |

## How it works

- `make demo-setup` creates the users in Keycloak with one shared password and an OTP credential from a known secret,
  and aligns an older local realm with `diligence-realm.json` where demo sign-ins depend on it (OTP for every role,
  #161; `amr` mapper and reference values, #160).
- `/auth/demo/<role>` runs Keycloak's own login and OTP forms on the server, exchanges the code with PKCE and the
  client secret, and starts an ordinary session (`startSession`, shared with `/auth/callback`). The tokens carry
  `amr` with `otp`, so step-up and every check behave as after a manual sign-in. Credentials never reach the browser.
- `make demo-seed` uses the real API as each role, so information barriers, four-eyes approval and the audit log apply
  as in normal use. Documents are generated PDFs with fictional content.
- Locally, Gotenberg gets a 90-second LibreOffice start timeout (`compose.yaml`): with 4 GiB of Docker memory the
  default 20 s is not enough and report generation fails.
