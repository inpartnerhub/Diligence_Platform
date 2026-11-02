# Apache Tika Server with Indonesian OCR (PRD DOC-06)

Upstream `apache/tika:4.2.0-1-full` bundles Tesseract OCR with English, German, French, Italian, Spanish and Japanese
trained data. Indonesian due diligence engagements (notarial deeds, contracts, tax receipts, bank statements)
require `ind.traineddata`.

## Building the image

```bash
docker build -t diligence-tika:local infrastructure/docker/tika
```

In production and staging Kubernetes clusters, publish this image to your private registry (GHCR/ECR) and reference
it in the processing deployment overlay.

