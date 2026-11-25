package com.inpartnerhub.diligence.ingestion.internal;

import java.io.IOException;
import java.io.InputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.sl.extractor.SlideShowExtractor;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.extractor.XSSFExcelExtractor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import com.inpartnerhub.diligence.ingestion.ScanException;

/**
 * In-process text extraction using PDFBox and Apache POI (already bundled in the backend) when an external Apache
 * Tika Server is not configured ({@code TIKA_URL} is empty). Scanned image OCR still requires Tika + Tesseract.
 */
final class EmbeddedTextExtractor {

    private EmbeddedTextExtractor() {
    }

    static String extract(InputStream content, String contentType, int maxChars) {
        if (maxChars <= 0) {
            return "";
        }
        var baseType = contentType == null ? "" : IngestionProperties.baseType(contentType);
        try {
            var raw = switch (baseType) {
                case "application/pdf" -> extractPdf(content);
                case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> extractDocx(content);
                case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> extractXlsx(content);
                case "application/vnd.openxmlformats-officedocument.presentationml.presentation" ->
                    extractPptx(content);
                case "text/plain", "text/csv", "message/rfc822" -> TikaTextExtractor.read(content, maxChars);
                default -> "";
            };
            return cap(raw, maxChars);
        } catch (IOException | RuntimeException ex) {
            throw new ScanException("Embedded text extraction failed: " + ex.getMessage(), false, ex);
        }
    }

    private static String extractPdf(InputStream content) throws IOException {
        try (var read = new RandomAccessReadBuffer(content);
                var document = Loader.loadPDF(read, IOUtils.createTempFileOnlyStreamCache())) {
            var stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private static String extractDocx(InputStream content) throws IOException {
        try (var doc = new XWPFDocument(content); var extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    private static String extractXlsx(InputStream content) throws IOException {
        try (var wb = new XSSFWorkbook(content); var extractor = new XSSFExcelExtractor(wb)) {
            extractor.setIncludeSheetNames(true);
            extractor.setIncludeHeadersFooters(false);
            return extractor.getText();
        }
    }

    private static String extractPptx(InputStream content) throws IOException {
        try (var ppt = new XMLSlideShow(content); var extractor = new SlideShowExtractor<>(ppt)) {
            extractor.setSlidesByDefault(true);
            extractor.setNotesByDefault(true);
            return extractor.getText();
        }
    }

    private static String cap(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        var trimmed = text.strip();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars).strip();
    }
}
