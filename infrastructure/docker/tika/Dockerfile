# Custom Apache Tika Server image bundled with Indonesian OCR language pack (PRD DOC-06).
# The upstream apache/tika:*-full image ships eng/deu/fra/ita/spa/jpn; this adds tesseract-ocr-ind.
FROM apache/tika:4.2.0-1-full

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       tesseract-ocr-ind \
    && rm -rf /var/lib/apt/lists/*

USER 35002:35002

