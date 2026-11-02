# Custom Apache Tika Server image bundled with Indonesian OCR language pack (PRD DOC-06).
# The upstream apache/tika:*-full image ships eng/deu/fra/ita/spa/jpn; this adds tesseract-ocr-ind.
FROM apache/tika:4.2.0-1-full

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       tesseract-ocr-ind \
       curl \
    && rm -rf /var/lib/apt/lists/*

USER 35002:35002
EXPOSE 9998

HEALTHCHECK --interval=20s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://127.0.0.1:9998/version || exit 1
