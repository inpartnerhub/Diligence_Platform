Closes #

## What

<!-- What changes and why; a table per area helps for larger changes. Link the issue / ADR. -->

## Tests

<!-- The commands actually run and their results (e.g. `make verify`, `./gradlew integrationTest --rerun-tasks`:
43/43), plus what was checked manually. Say what was not run. -->

## Outside this repository

<!-- Steps needed in deployed environments (realm, Vault, Terraform apply, data migration), or "none". -->

## Checklist

- [ ] Tests added or updated (unit, integration as relevant)
- [ ] New migration sorts after every migration on `main`, and is backward compatible (expand → migrate → contract)
- [ ] API changes are backward compatible within `/api/v1`; frontend `types.ts` updated
- [ ] Breaking changes (API, configuration, required steps outside the repository) marked with `!` / `BREAKING CHANGE:`
- [ ] No secrets or real personal data in code, config, logs or fixtures; `--no-verify` only after a clean gitleaks scan
- [ ] Personal data, retention or subprocessor changes called out (UU PDP)
- [ ] Documentation / ADR updated if architecture, module boundaries or security controls changed
