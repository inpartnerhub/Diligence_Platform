package com.inpartnerhub.diligence.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.inpartnerhub.diligence.ingestion.ScanException;

class EmbeddedIngestionTest {

    @Test
    void detectsCommonAllowedFormatsByMagicBytesAndContainer() throws Exception {
        assertThat(EmbeddedContentTypeDetector.detect(stream(TestPdfs.pages(1)), "doc.pdf"))
                .isEqualTo("application/pdf");

        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", png);
        assertThat(EmbeddedContentTypeDetector.detect(stream(png.toByteArray()), "scan.png"))
                .isEqualTo("image/png");

        assertThat(EmbeddedContentTypeDetector.detect(stream(docx("Sample agreement")), "contract.docx"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(EmbeddedContentTypeDetector.detect(stream(xlsx("Revenue", 12500)), "model.xlsx"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(EmbeddedContentTypeDetector.detect(stream(pptx("Board deck")), "deck.pptx"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.presentationml.presentation");

        assertThat(EmbeddedContentTypeDetector.detect(
                stream("col1,col2\nval1,val2\n".getBytes(StandardCharsets.UTF_8)), "data.csv"))
                .isEqualTo("text/csv");
        assertThat(EmbeddedContentTypeDetector.detect(
                stream("Plain notes".getBytes(StandardCharsets.UTF_8)), "notes.txt"))
                .isEqualTo("text/plain");
    }

    @Test
    void rejectsSpoofedExtensionsAndGenericArchives() throws Exception {
        assertThat(EmbeddedContentTypeDetector.detect(
                stream("MZ\0fake-exe-payload".getBytes(StandardCharsets.UTF_8)), "trojan.pdf"))
                .isEqualTo("application/octet-stream");
        assertThat(EmbeddedContentTypeDetector.detect(
                stream("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)), "evil.html"))
                .isEqualTo("application/octet-stream");

        var genericZip = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(genericZip)) {
            zip.putNextEntry(new ZipEntry("payload.bin"));
            zip.write(new byte[] { 1, 2, 3 });
            zip.closeEntry();
        }
        assertThat(EmbeddedContentTypeDetector.detect(stream(genericZip.toByteArray()), "fake.docx"))
                .isEqualTo("application/zip");
    }

    @Test
    void extractsTextInProcessForPdfOfficeAndPlainText() throws Exception {
        assertThat(EmbeddedTextExtractor.extract(
                stream(TestPdfs.pages(2)), "application/pdf", 5000))
                .contains("Halaman 1", "Halaman 2");

        assertThat(EmbeddedTextExtractor.extract(
                stream(docx("Perjanjian Kerahasiaan NDA")),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 5000))
                .contains("Perjanjian Kerahasiaan NDA");

        assertThat(EmbeddedTextExtractor.extract(
                stream(xlsx("EBITDA", 42000)),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 5000))
                .contains("EBITDA", "42000");

        assertThat(EmbeddedTextExtractor.extract(
                stream(pptx("Executive Summary")),
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", 5000))
                .contains("Executive Summary");

        assertThat(EmbeddedTextExtractor.extract(
                stream("  1234567890  ".getBytes(StandardCharsets.UTF_8)), "text/plain; charset=UTF-8", 5))
                .isEqualTo("123");

        assertThat(EmbeddedTextExtractor.extract(stream(new byte[] { 1, 2, 3 }), "image/png", 5000))
                .isEmpty();

        assertThatThrownBy(() -> EmbeddedTextExtractor.extract(
                stream("not-a-pdf".getBytes(StandardCharsets.UTF_8)), "application/pdf", 5000))
                .isInstanceOfSatisfying(ScanException.class, ex -> assertThat(ex.isTransientFailure()).isFalse());
    }

    @Test
    void adaptersFallBackCleanlyWhenExternalUrlsAreUnset() throws Exception {
        var props = new IngestionProperties(null, null, "", 3310, null, null);

        var scanner = new ClamAvScanner(props);
        assertThat(scanner.scan(stream(new byte[] { 1, 2, 3 })).clean()).isTrue();

        var detector = new TikaContentTypeDetector(RestClient.builder(), props);
        assertThat(detector.detect(stream(docx("Embedded detection")), "test.docx"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        var extractor = new TikaTextExtractor(RestClient.builder(), props);
        assertThat(extractor.extract(stream(docx("Embedded extraction")),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 1000))
                .contains("Embedded extraction");

        var renderer = new GotenbergPreviewRenderer(RestClient.builder(), props);
        assertThat(renderer.supports("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .isFalse();
        assertThatThrownBy(() -> renderer.renderPdf(stream(new byte[0]), "test.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", Path.of("unused.pdf")))
                .isInstanceOfSatisfying(ScanException.class, ex -> assertThat(ex.isTransientFailure()).isFalse());
    }

    private static ByteArrayInputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    private static byte[] docx(String text) throws IOException {
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText(text);
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] xlsx(String label, double value) throws IOException {
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("Summary");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue(label);
            row.createCell(1).setCellValue(value);
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] pptx(String title) throws IOException {
        try (var ppt = new XMLSlideShow(); var out = new ByteArrayOutputStream()) {
            var slide = ppt.createSlide();
            slide.createTextBox().setText(title);
            ppt.write(out);
            return out.toByteArray();
        }
    }
}
