# Document-processing namespace (`diligence-processing`)

ClamAV (malware scan), Apache Tika (type detection, text and OCR) and Gotenberg (LibreOffice previews) parse every
uploaded file, including files from targets and clients. Parser bugs are the most likely way a hostile file attacks
the platform, so these services run apart from the application (#92):

| Control | How |
|---------|-----|
| Network | Default deny. Ingress only from `diligence-backend` pods of namespaces labelled `app.kubernetes.io/part-of: diligence`, on 3310/9998/3000. Egress only DNS; ClamAV additionally HTTPS to public addresses for signature updates. No access to the metadata endpoint, other namespaces or the internet. |
| Pods | Pod Security `restricted`: non-root (ClamAV 1000, Tika 35002, Gotenberg 1001), read-only root filesystem, all capabilities dropped, `RuntimeDefault` seccomp, no service-account token. Writable paths are size-limited `emptyDir`s. |
| Images | ClamAV and Gotenberg pinned by digest; Tika is our image (`infrastructure/docker/tika`, base pinned by digest) built and scanned by the *Processing images* workflow. Dependabot proposes updates. |
| Capacity | Requests and limits per container, two replicas each, a PodDisruptionBudget each. |

ClamAV does not use the image's `/init` (it needs root): an init container downloads the signatures, `clamd` scans,
and a `freshclam` sidecar updates them; `clamd` reloads them itself (`SelfCheck`). Configuration is in the
`clamav-config` ConfigMap, mounted at `/etc/clamav-config` so the image's signing certificates in `/etc/clamav`
stay visible. Readiness uses `clamdscan --ping`, because `clamd` accepts TCP before its signatures are loaded.

## Deploy

One processing namespace per cluster serves every Diligence environment in it (the backend's `TIKA_URL`,
`GOTENBERG_URL` and `CLAMAV_HOST` point at `*.diligence-processing.svc`).

```bash
cd infrastructure/kubernetes/processing
kustomize edit set image ghcr.io/inpartnerhub/diligence-tika=ghcr.io/inpartnerhub/diligence-tika:sha-<commit>
kubectl apply -k .
kubectl -n diligence-processing rollout status deploy/clamav deploy/tika deploy/gotenberg
```

The signatures are re-downloaded when a ClamAV pod starts (about 300 MB from the ClamAV CDN, which rate-limits
heavy users). For frequent restarts or several clusters, point `DatabaseMirror` at a private mirror.

## Verified locally (Docker, same user, read-only root and dropped capabilities)

- ClamAV 1.5.4: signatures downloaded by `freshclam`, `clamd` answered `PING` after about 8 s, EICAR reported as
  `Eicar-Test-Signature FOUND` over `INSTREAM`, a clean stream `OK`; about 1 GiB of memory.
- Gotenberg 8.40.0: `/forms/libreoffice/convert` returned a PDF; a document with external links made no outbound
  request.
- Tika 4.2.0 (our image): text and PDF extraction; about 0.8 GiB of memory. OCR was not exercised.

Not verifiable here: the NetworkPolicies themselves (they need a CNI that enforces them) and Pod Security admission;
check both on the first deployment (`kubectl -n diligence-processing exec deploy/tika -- curl -m 5 https://example.com`
must fail).
