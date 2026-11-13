# Document-processing namespace (`diligence-processing`)

ClamAV (malware scan), Apache Tika (type detection, text and OCR) and Gotenberg (LibreOffice previews) parse every
uploaded file, including files from targets and clients. Parser bugs are the most likely way a hostile file attacks
the platform, so these services run apart from the application (#92):

| Control | How |
|---------|-----|
| Network | Default deny. Ingress only from `diligence-backend` pods of namespaces labelled `app.kubernetes.io/part-of: diligence`, on 3310/9998/3000. Egress only DNS; ClamAV additionally HTTPS to public addresses for signature updates. No access to the metadata endpoint, other namespaces or the internet. |
| Pods | Pod Security `restricted`: non-root with UIDs above 10000 (ClamAV 10100, Gotenberg 10101, Tika 35002), read-only root filesystem, all capabilities dropped, `RuntimeDefault` seccomp, no service-account token. Writable paths are size-limited `emptyDir`s. |
| Images | All three are rebuilt into GHCR, the only trusted registry (`.trivy/data/trusted-registries.yaml`): `infrastructure/docker/{clamav,gotenberg,tika}` start from digest-pinned upstream images, apply security updates and move the service user above UID 10000. The *Processing images* workflow builds, Trivy-scans (fails on fixable High/Critical) and pushes them. Dependabot proposes base updates. |
| Capacity | Requests and limits per container, two replicas each, a PodDisruptionBudget each. |

ClamAV does not use the image's `/init` (it needs root): an init container downloads the signatures, `clamd` scans,
and a `freshclam` sidecar updates them; `clamd` reloads them itself (`SelfCheck`). Configuration is in the
`clamav-config` ConfigMap, mounted at `/etc/clamav-config` so the image's signing certificates in `/etc/clamav`
stay visible. Readiness uses `clamdscan --ping`, because `clamd` accepts TCP before its signatures are loaded.

## Deploy

One processing namespace per cluster serves every Diligence environment in it (the backend's `TIKA_URL`,
`GOTENBERG_URL` and `CLAMAV_HOST` point at `*.diligence-processing.svc`).

The images are pinned by digest in `kustomization.yaml` (#166); CI refuses a moving tag. To roll out new builds,
update the three digests from the *Processing images* run on `main` (its "pushing manifest for …:main@sha256:…" log
lines; Gotenberg also in `../base/search/kustomization.yaml`) in a pull request, then:

```bash
cd infrastructure/kubernetes/processing
kubectl apply -k .
kubectl -n diligence-processing rollout status deploy/clamav deploy/tika deploy/gotenberg
```

The signatures are re-downloaded when a ClamAV pod starts (about 300 MB from the ClamAV CDN, which rate-limits
heavy users). For frequent restarts or several clusters, point `DatabaseMirror` at a private mirror.

## Verified locally (Docker, read-only root and dropped capabilities; with the upstream images and their original UIDs 1000/1001, before the GHCR rebuilds moved them above 10000)

- ClamAV 1.5.4: signatures downloaded by `freshclam`, `clamd` answered `PING` after about 8 s, EICAR reported as
  `Eicar-Test-Signature FOUND` over `INSTREAM`, a clean stream `OK`; about 1 GiB of memory.
- Gotenberg 8.40.0: `/forms/libreoffice/convert` returned a PDF; a document with external links made no outbound
  request.
- Tika 4.2.0 (our image): text and PDF extraction; about 0.8 GiB of memory. OCR was not exercised.

Not verifiable here: the NetworkPolicies themselves and Pod Security admission. The EKS module enables VPC CNI network
policy enforcement (#157); after every cluster or add-on change run
`infrastructure/kubernetes/scripts/verify-network-policies.sh diligence-<env>`, which tries connections the policies
must block (processing pods to the internet, the backend and the metadata endpoint) and checks that the backend still
reaches Tika. It must report "NetworkPolicies are enforced".
