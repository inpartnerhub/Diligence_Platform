# Gotenberg (LibreOffice previews) for the processing namespace (#92), rebuilt into GHCR (the only trusted
# registry, .trivy/data) and scanned by the Processing images workflow. The gotenberg user moves above UID 10000
# (Trivy KSV-0020/0021).
FROM gotenberg/gotenberg:9.2.0@sha256:e1840b4c1ee113078cc153b8359433c9511ab544c1a15cd45b09f81c9329118a

USER root
RUN apt-get update \
    && apt-get -y upgrade \
    && rm -rf /var/lib/apt/lists/* \
    && groupmod -g 10101 gotenberg \
    && usermod -u 10101 -g 10101 gotenberg \
    && chown -R 10101:10101 /home/gotenberg

USER 10101:10101

# For docker run/compose; Kubernetes uses its own probes.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -sf http://127.0.0.1:3000/health || exit 1
