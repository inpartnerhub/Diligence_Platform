# Gotenberg (LibreOffice previews) for the processing namespace (#92), rebuilt into GHCR (the only trusted
# registry, .trivy/data) and scanned by the Processing images workflow. The gotenberg user moves above UID 10000
# (Trivy KSV-0020/0021).
FROM gotenberg/gotenberg:9.9.2@sha256:f6b4b6f7c8ae0ca7805f9e105ef989bf13b02d548378fa784c3630a9c479c31b

USER root
RUN apt-get update \
    && apt-get -y upgrade \
    && apt-get -y dist-upgrade \
    && rm -rf /var/lib/apt/lists/* \
    && groupmod -g 10101 gotenberg \
    && usermod -u 10101 -g 10101 gotenberg \
    && chown -R 10101:10101 /home/gotenberg

USER 10101:10101

# For docker run/compose; Kubernetes uses its own probes.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -sf http://127.0.0.1:3000/health || exit 1
