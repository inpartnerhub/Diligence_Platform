# ClamAV for the processing namespace (#92), rebuilt into GHCR (the only trusted registry, .trivy/data) and scanned
# by the Processing images workflow. The clamav user moves above UID 10000 (Trivy KSV-0020/0021); the Kubernetes
# manifest runs freshclam and clamd directly as that user, without the image's root /init.
FROM clamav/clamav-debian:1.5.4@sha256:76a9c3b63764b78178dc68fa7efcceca0cde2c8c9d4f925a8e697cb4511f8fdc

RUN apt-get update \
    && apt-get -y upgrade \
    && rm -rf /var/lib/apt/lists/* \
    && groupmod -g 10100 clamav \
    && usermod -u 10100 -g 10100 clamav \
    && chown -R 10100:10100 /var/lib/clamav /var/log/clamav

USER 10100:10100

# For docker run/compose; Kubernetes uses its own probes (clamdscan --ping against /etc/clamav-config).
HEALTHCHECK --interval=30s --timeout=10s --start-period=180s --retries=3 CMD clamdscan --ping 1 || exit 1
