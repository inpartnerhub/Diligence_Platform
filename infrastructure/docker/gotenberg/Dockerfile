# Gotenberg (LibreOffice previews) for the processing namespace (#92), rebuilt into GHCR (the only trusted
# registry, .trivy/data) and scanned by the Processing images workflow. The gotenberg user moves above UID 10000
# (Trivy KSV-0020/0021).
FROM gotenberg/gotenberg:8.40.0@sha256:53073e798c8ef39ea2187e44cb67bab77894ea2509100b94c4fe3b461f7b2996

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
