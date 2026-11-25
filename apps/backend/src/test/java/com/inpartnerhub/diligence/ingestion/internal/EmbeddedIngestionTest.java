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

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.health.contributor.Status;
import org.springframework.web.client.RestClient;

import com.inpartnerhub.diligence.ingestion.ScanException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

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
        assertThat(EmbeddedContentTypeDetector.detect(stream(xls("Revenue", 9900)), "legacy.xls"))
                .isEqualTo("application/vnd.ms-excel");
        assertThat(EmbeddedContentTypeDetector.detect(stream(odt("Akta Perusahaan")), "akta.odt"))
                .isEqualTo("application/vnd.oasis.opendocument.text");
    }

    @Test
    void detectsRealOle2ContainerTypeEvenWhenExtensionIsSpoofed() throws Exception {
        assertThat(EmbeddedContentTypeDetector.detect(stream(xls("EBITDA", 42000)), "spoofed.doc"))
                .isEqualTo("application/vnd.ms-excel");
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
                stream(xls("Gross Profit", 75000)),
                "application/vnd.ms-excel", 5000))
                .contains("LegacySummary", "Gross Profit", "75000");

        assertThat(EmbeddedTextExtractor.extract(
                stream(odt("Perjanjian Investasi")),
                "application/vnd.oasis.opendocument.text", 5000))
                .contains("Perjanjian Investasi");

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

        var requiredMeters = new SimpleMeterRegistry();
        var requiredScanner = new ClamAvScanner(props, provider(requiredMeters));
        assertThatThrownBy(() -> requiredScanner.scan(stream(new byte[] { 1, 2, 3 })))
                .isInstanceOfSatisfying(ScanException.class, ex -> assertThat(ex.isTransientFailure()).isFalse());
        assertThat(requiredScanner.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(requiredMeters.get("diligence_ingestion_av_enabled").gauge().value()).isZero();

        var externalProps = new IngestionProperties(null, null, "", 3310, null, null,
                null, null, null, null, null, null, null, "external");
        var externalMeters = new SimpleMeterRegistry();
        var externalScanner = new ClamAvScanner(externalProps, provider(externalMeters));
        assertThat(externalScanner.scan(stream(new byte[] { 1, 2, 3 })).clean()).isTrue();
        assertThat(externalScanner.health().getStatus()).isEqualTo(Status.UP);
        assertThat(externalMeters.get("diligence_ingestion_av_enabled").gauge().value()).isZero();

        var activeProps = new IngestionProperties(null, null, "localhost", 3310, null, null);
        var activeMeters = new SimpleMeterRegistry();
        var activeScanner = new ClamAvScanner(activeProps, provider(activeMeters));
        assertThat(activeScanner.health().getStatus()).isEqualTo(Status.UP);
        assertThat(activeMeters.get("diligence_ingestion_av_enabled").gauge().value()).isEqualTo(1.0);

        assertThat(IngestionProperties.normalizeClamavMode(null)).isEqualTo("required");
        assertThat(IngestionProperties.normalizeClamavMode("  EXTERNAL ")).isEqualTo("external");
        assertThat(IngestionProperties.normalizeClamavMode("Disabled")).isEqualTo("disabled");
        assertThat(IngestionProperties.normalizeClamavMode("bogus")).isEqualTo("required");

        var detector = new TikaContentTypeDetector(RestClient.builder(), props);
        assertThat(detector.detect(stream(docx("Embedded detection")), "test.docx"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        var extractor = new TikaTextExtractor(RestClient.builder(), props);
        assertThat(extractor.extract(stream(docx("Embedded extraction")),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 1000))
                .contains("Embedded extraction");

        var renderer = new GotenbergPreviewRenderer(RestClient.builder(), props);
        assertThat(renderer.supports("application/msword")).isFalse();
        assertThatThrownBy(() -> renderer.renderPdf(stream(new byte[0]), "legacy.doc",
                "application/msword", Path.of("unused.pdf")))
                .isInstanceOfSatisfying(ScanException.class, ex -> assertThat(ex.isTransientFailure()).isFalse());
    }

    private static org.springframework.beans.factory.ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("meterRegistry", registry);
        return beans.getBeanProvider(MeterRegistry.class);
    }

    private static ByteArrayInputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    private static byte[] xls(String label, double value) throws IOException {
        try (var wb = new HSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("LegacySummary");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue(label);
            row.createCell(1).setCellValue(value);
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] odt(String text) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("mimetype"));
            zip.write("application/vnd.oasis.opendocument.text".getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("content.xml"));
            var xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                                             xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
                      <office:body>
                        <office:text>
                          <text:p>%s</text:p>
                        </office:text>
                      </office:body>
                    </office:document-content>
                    """.formatted(text);
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
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
