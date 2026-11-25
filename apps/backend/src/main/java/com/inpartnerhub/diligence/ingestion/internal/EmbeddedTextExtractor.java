package com.inpartnerhub.diligence.ingestion.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.extractor.ExcelExtractor;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.sl.extractor.SlideShowExtractor;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.extractor.XSSFExcelExtractor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import com.inpartnerhub.diligence.ingestion.ScanException;

/**
 * In-process text extraction using PDFBox, Apache POI and bounded StAX ODF parsing when an external Apache Tika Server
 * is not configured ({@code TIKA_URL} is empty). Scanned image OCR still requires Tika + Tesseract.
 */
final class EmbeddedTextExtractor {

    static final int MAX_INPUT_BYTES = 32 * 1024 * 1024;
    static final long MAX_ENTRY_BYTES = 32L * 1024 * 1024;
    static final long MAX_TEXT_BYTES = 10L * 1024 * 1024;
    static final double MIN_INFLATE_RATIO = 0.01;
    static final int MAX_ODF_XML_BYTES = 16 * 1024 * 1024;
    static final int MAX_ZIP_ENTRIES = 1024;

    static {
        if (ZipSecureFile.getMinInflateRatio() < MIN_INFLATE_RATIO) {
            ZipSecureFile.setMinInflateRatio(MIN_INFLATE_RATIO);
        }
        if (ZipSecureFile.getMaxEntrySize() > MAX_ENTRY_BYTES) {
            ZipSecureFile.setMaxEntrySize(MAX_ENTRY_BYTES);
        }
        if (ZipSecureFile.getMaxTextSize() > MAX_TEXT_BYTES) {
            ZipSecureFile.setMaxTextSize(MAX_TEXT_BYTES);
        }
    }

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
                case "application/vnd.ms-excel" -> extractXls(content);
                case "application/vnd.oasis.opendocument.text",
                        "application/vnd.oasis.opendocument.spreadsheet",
                        "application/vnd.oasis.opendocument.presentation" -> extractOdf(content, maxChars);
                case "text/plain", "text/csv", "message/rfc822" -> TikaTextExtractor.read(content, maxChars);
                default -> "";
            };
            return cap(raw, maxChars);
        } catch (ScanException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new ScanException("Embedded text extraction failed: " + ex.getMessage(), false, ex);
        }
    }

    private static InputStream boundedStream(InputStream content) throws IOException {
        var bytes = content.readNBytes(MAX_INPUT_BYTES + 1);
        if (bytes.length > MAX_INPUT_BYTES) {
            throw new ScanException("Document exceeds maximum embedded extraction size (" + MAX_INPUT_BYTES + " bytes)",
                    false, null);
        }
        return new ByteArrayInputStream(bytes);
    }

    private static String extractPdf(InputStream content) throws IOException {
        try (var read = new RandomAccessReadBuffer(content);
                var document = Loader.loadPDF(read, IOUtils.createTempFileOnlyStreamCache())) {
            var stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private static String extractDocx(InputStream content) throws IOException {
        try (var bounded = boundedStream(content);
                var doc = new XWPFDocument(bounded);
                var extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    private static String extractXlsx(InputStream content) throws IOException {
        try (var bounded = boundedStream(content);
                var wb = new XSSFWorkbook(bounded);
                var extractor = new XSSFExcelExtractor(wb)) {
            extractor.setIncludeSheetNames(true);
            extractor.setIncludeHeadersFooters(false);
            return extractor.getText();
        }
    }

    private static String extractPptx(InputStream content) throws IOException {
        try (var bounded = boundedStream(content);
                var ppt = new XMLSlideShow(bounded);
                var extractor = new SlideShowExtractor<>(ppt)) {
            extractor.setSlidesByDefault(true);
            extractor.setNotesByDefault(true);
            return extractor.getText();
        }
    }

    private static String extractXls(InputStream content) throws IOException {
        try (var bounded = boundedStream(content);
                var wb = new HSSFWorkbook(bounded);
                var extractor = new ExcelExtractor(wb)) {
            extractor.setIncludeSheetNames(true);
            extractor.setIncludeHeadersFooters(false);
            return extractor.getText();
        }
    }

    private static String extractOdf(InputStream content, int maxChars) throws IOException {
        try (var bounded = boundedStream(content); var zip = new ZipInputStream(bounded)) {
            ZipEntry entry;
            var entries = 0;
            while (entries++ < MAX_ZIP_ENTRIES && (entry = zip.getNextEntry()) != null) {
                if ("content.xml".equals(entry.getName())) {
                    var xmlBytes = readBoundedEntry(zip, MAX_ODF_XML_BYTES);
                    return parseOdfContentXml(xmlBytes, maxChars);
                }
            }
            return "";
        }
    }

    private static byte[] readBoundedEntry(ZipInputStream zip, int maxBytes) throws IOException {
        var out = new ByteArrayOutputStream();
        var buf = new byte[8192];
        int total = 0;
        int read;
        while ((read = zip.read(buf)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new ScanException("ODF content.xml exceeds maximum uncompressed size (" + maxBytes + " bytes)",
                        false, null);
            }
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    private static String parseOdfContentXml(byte[] xmlBytes, int maxChars) {
        var factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        try {
            var reader = factory.createXMLStreamReader(new ByteArrayInputStream(xmlBytes));
            try {
                var sb = new StringBuilder();
                var lastWasSpace = true;
                while (reader.hasNext() && sb.length() < maxChars) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                        var text = reader.getText();
                        if (text != null && !text.isEmpty()) {
                            sb.append(text);
                            lastWasSpace = Character.isWhitespace(text.charAt(text.length() - 1));
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT && !lastWasSpace) {
                        var localName = reader.getLocalName();
                        if ("p".equals(localName) || "h".equals(localName) || "table-cell".equals(localName)
                                || "table-row".equals(localName)) {
                            sb.append('\n');
                            lastWasSpace = true;
                        } else if ("s".equals(localName) || "tab".equals(localName)) {
                            sb.append(' ');
                            lastWasSpace = true;
                        }
                    } else if (event == XMLStreamConstants.START_ELEMENT) {
                        if ("line-break".equals(reader.getLocalName()) && !lastWasSpace) {
                            sb.append('\n');
                            lastWasSpace = true;
                        }
                    }
                }
                return sb.toString();
            } finally {
                reader.close();
            }
        } catch (XMLStreamException ex) {
            throw new ScanException("Embedded ODF XML parsing failed: " + ex.getMessage(), false, ex);
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
