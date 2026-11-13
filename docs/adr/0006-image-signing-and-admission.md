# ADR-0006: Signed images and admission control with Kyverno

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** Maintainer (repository owner)

## Context

The platform runs confidential due-diligence documents. Images are built in GitHub Actions, scanned by Trivy and pushed
to GHCR with SBOM and provenance, and since #166 deployed by digest. Nothing in the cluster checked that a running
image came from that pipeline: anyone able to create a Pod in a Diligence namespace could run any image, and the
"trusted registry" list in `.trivy/data` was only a scanner rule. SECURITY.md listed image signing and admission
verification as a pre-production gap (#167).

## Decision

1. Every image pushed by `.github/workflows/container-image.yml` (backend, frontend, ClamAV, Gotenberg, Tika) is signed
   keyless with cosign: Sigstore issues a short-lived certificate for the workflow's GitHub OIDC identity, and the
   signature is stored in GHCR and the public Rekor transparency log. There is no signing key to store or rotate.
2. Kyverno (≥ 1.13), installed with the other cluster add-ons, enforces `infrastructure/kubernetes/policies`:
   Pods in `diligence-*` namespaces may only use images from `ghcr.io/inpartnerhub/`, and `diligence-*` images must
   carry a signature whose certificate identity is that workflow on `refs/heads/main`, issued by
   `token.actions.githubusercontent.com`. The image reference is pinned to the verified digest.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Kyverno `verifyImages` | One engine for signatures and further rules (registries, tags, Pod settings); widely used | Another cluster add-on with its own upgrades; webhook availability matters |
| Sigstore policy-controller | Purpose-built for cosign, small | Only signature checks; other admission rules would need a second engine |
| Key-based cosign signing | Works without the public Rekor log | A private key to protect and rotate in CI |
| No admission control (sign only) | Nothing to operate | A signature nobody checks does not stop an unsigned image |

## Consequences

- Images built before this change are unsigned: rebuild them on `main` (backend, frontend; *Processing images*
  workflow) and update the processing digests (#166) before enforcing.
- A Kyverno outage blocks new Pods in the Diligence namespaces (the webhook fails closed); run Kyverno with more than
  one replica and alert on it.
- Image signatures depend on the availability of Sigstore's public services at build time (Fulcio, Rekor).
- Changing the workflow file name or the branch that builds release images requires updating the policy's subject.
