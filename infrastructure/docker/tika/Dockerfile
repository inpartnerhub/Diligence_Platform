# Custom Apache Tika Server image bundled with Indonesian OCR language pack (PRD DOC-06).
# The upstream apache/tika:*-full image ships eng/deu/fra/ita/spa/jpn; this adds tesseract-ocr-ind.
# Pinned by digest (#92); Dependabot (docker) proposes updates.
FROM apache/tika:4.2.0-1-full@sha256:4774eaac44f86cdf648ae7b5a367016eaf6f9052adec31e5cff07cff1a0c6e24

USER root

# Security updates of the base (e.g. apt itself), and no pebble: Ubuntu's service manager is unused here and its Go
# runtime carries open CVEs (#92).
RUN apt-get update \
    && apt-get -y upgrade \
    && apt-get install -y --no-install-recommends \
       tesseract-ocr-ind \
       curl \
    && (pkg=$(dpkg -S /usr/bin/pebble 2>/dev/null | cut -d: -f1); \
        if [ -n "$pkg" ]; then apt-get purge -y "$pkg"; else rm -f /usr/bin/pebble; fi) \
    && rm -rf /var/lib/apt/lists/*

USER 35002:35002
EXPOSE 9998

HEALTHCHECK --interval=20s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://127.0.0.1:9998/version || exit 1
