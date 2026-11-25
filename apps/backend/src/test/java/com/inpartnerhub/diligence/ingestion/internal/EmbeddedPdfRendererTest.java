package com.inpartnerhub.diligence.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import com.inpartnerhub.diligence.ingestion.ScanException;

class EmbeddedPdfRendererTest {

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    @TempDir
    Path tempDir;

    @Test
    void rendersDocxWithHeadingsPageBreaksColoredTableAndNonWinAnsiCharacters() throws Exception {
        byte[] docxBytes;
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            var title = doc.createParagraph();
            title.setAlignment(ParagraphAlignment.CENTER);
            var titleRun = title.createRun();
            titleRun.setBold(true);
            titleRun.setFontSize(22);
            titleRun.setColor("1F3864");
            titleRun.setText("Due Diligence Report \uD83D\uDCC4");

            var body = doc.createParagraph();
            var bodyRun = body.createRun();
            bodyRun.setText("Threshold \u2264 5% and \u2265 1% \u2022 Risk assessment");

            var table = doc.createTable(2, 2);
            var header0 = table.getRow(0).getCell(0);
            header0.getParagraphs().getFirst().createRun().setText("Risk level");
            var header1 = table.getRow(0).getCell(1);
            header1.getParagraphs().getFirst().createRun().setText("Count");

            var criticalCell = table.getRow(1).getCell(0);
            criticalCell.setColor("CF1322");
            var critRun = criticalCell.getParagraphs().getFirst().createRun();
            critRun.setBold(true);
            critRun.setColor("FFFFFF");
            critRun.setText("Critical");

            var countCell = table.getRow(1).getCell(1);
            countCell.getParagraphs().getFirst().createRun().setText("3");

            doc.createParagraph().createRun().addBreak(BreakType.PAGE);

            var pageTwoHeading = doc.createParagraph();
            var p2Run = pageTwoHeading.createRun();
            p2Run.setBold(true);
            p2Run.setFontSize(16);
            p2Run.setText("Section Two Findings");

            doc.write(out);
            docxBytes = out.toByteArray();
        }

        var pdf = tempDir.resolve("report.pdf");
        EmbeddedPdfRenderer.renderPdf(new ByteArrayInputStream(docxBytes), DOCX, pdf);

        try (var loaded = Loader.loadPDF(pdf.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            assertThat(loaded.getNumberOfPages()).isGreaterThanOrEqualTo(2);
            var stripper = new PDFTextStripper();
            var allText = stripper.getText(loaded);
            assertThat(allText).contains("Due Diligence Report");
            assertThat(allText).contains("Threshold <= 5% and >= 1% * Risk assessment");
            assertThat(allText).contains("Critical");
            assertThat(allText).contains("Section Two Findings");
            stripper.setStartPage(2);
            stripper.setEndPage(2);
            assertThat(stripper.getText(loaded)).contains("Section Two Findings");
        }
    }

    @Test
    void rendersXlsxWithHeaderAndDataRows() throws Exception {
        byte[] xlsxBytes;
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("Request list");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Number");
            header.createCell(1).setCellValue("Category");
            header.createCell(2).setCellValue("Description");

            var row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("LEG-001");
            row1.createCell(1).setCellValue("Corporate");
            row1.createCell(2).setCellValue("Articles of association and amendments");

            sheet.setColumnWidth(0, 12 * 256);
            sheet.setColumnWidth(1, 18 * 256);
            sheet.setColumnWidth(2, 48 * 256);

            wb.write(out);
            xlsxBytes = out.toByteArray();
        }

        var pdf = tempDir.resolve("requests.pdf");
        EmbeddedPdfRenderer.renderPdf(new ByteArrayInputStream(xlsxBytes), XLSX, pdf);

        try (var loaded = Loader.loadPDF(pdf.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            assertThat(loaded.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            var text = new PDFTextStripper().getText(loaded);
            assertThat(text).contains("Request list", "Number", "LEG-001", "Corporate",
                    "Articles of association and amendments");
        }
    }

    @Test
    void rendersPptxAndTextAndCsv() throws Exception {
        byte[] pptxBytes;
        try (var ppt = new XMLSlideShow(); var out = new ByteArrayOutputStream()) {
            var slide = ppt.createSlide();
            slide.createTextBox().setText("Management Presentation");
            slide.createTextBox().setText("FY2025 Revenue Growth: 18%");
            ppt.write(out);
            pptxBytes = out.toByteArray();
        }

        var pptxPdf = tempDir.resolve("deck.pdf");
        EmbeddedPdfRenderer.renderPdf(new ByteArrayInputStream(pptxBytes), PPTX, pptxPdf);
        try (var loaded = Loader.loadPDF(pptxPdf.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            assertThat(loaded.getNumberOfPages()).isEqualTo(1);
            assertThat(new PDFTextStripper().getText(loaded)).contains("Management Presentation",
                    "FY2025 Revenue Growth: 18%");
        }

        var txtPdf = tempDir.resolve("notes.pdf");
        EmbeddedPdfRenderer.renderPdf(
                new ByteArrayInputStream("Line 1: Confidential\nLine 2: Ratio \u2264 2.5x".getBytes(
                        StandardCharsets.UTF_8)),
                "text/plain; charset=UTF-8", txtPdf);
        try (var loaded = Loader.loadPDF(txtPdf.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            assertThat(new PDFTextStripper().getText(loaded)).contains("Line 1: Confidential", "Ratio <= 2.5x");
        }

        var csvPdf = tempDir.resolve("data.pdf");
        EmbeddedPdfRenderer.renderPdf(
                new ByteArrayInputStream("col1,col2\nalpha,beta\n".getBytes(StandardCharsets.UTF_8)),
                "text/csv", csvPdf);
        try (var loaded = Loader.loadPDF(csvPdf.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            assertThat(new PDFTextStripper().getText(loaded)).contains("col1,col2", "alpha,beta");
        }
    }

    @Test
    void gotenbergPreviewRendererFallsBackToEmbeddedWhenUrlIsUnset() throws Exception {
        var props = new IngestionProperties(null, null, "", 3310, null, null);
        var renderer = new GotenbergPreviewRenderer(RestClient.builder(), props);

        assertThat(renderer.supports(DOCX)).isTrue();
        assertThat(renderer.supports(XLSX)).isTrue();
        assertThat(renderer.supports(PPTX)).isTrue();
        assertThat(renderer.supports("text/plain; charset=UTF-8")).isTrue();
        assertThat(renderer.supports("text/csv")).isTrue();
        assertThat(renderer.supports("application/msword")).isFalse();
        assertThat(renderer.supports("application/pdf")).isFalse();
        assertThat(renderer.supports(null)).isFalse();

        byte[] docxBytes;
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Fallback rendered contract");
            doc.write(out);
            docxBytes = out.toByteArray();
        }
        var target = tempDir.resolve("fallback.pdf");
        renderer.renderPdf(new ByteArrayInputStream(docxBytes), "contract.docx", DOCX, target);

        assertThat(Files.size(target)).isGreaterThan(0);
        try (var loaded = Loader.loadPDF(target.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            assertThat(new PDFTextStripper().getText(loaded)).contains("Fallback rendered contract");
        }
    }

    @Test
    void rejectsStreamsExceeding32MiBLimitWithNonTransientScanException() {
        var oversized = new InputStream() {
            private int remaining = EmbeddedPdfRenderer.MAX_INPUT_BYTES + 1;

            @Override
            public int read() {
                if (remaining <= 0) {
                    return -1;
                }
                remaining--;
                return 'a';
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (remaining <= 0) {
                    return -1;
                }
                var count = Math.min(len, remaining);
                java.util.Arrays.fill(b, off, off + count, (byte) 'a');
                remaining -= count;
                return count;
            }
        };

        var target = tempDir.resolve("oversized.pdf");
        assertThatThrownBy(() -> EmbeddedPdfRenderer.renderPdf(oversized, "text/plain", target))
                .isInstanceOfSatisfying(ScanException.class, ex -> {
                    assertThat(ex.isTransientFailure()).isFalse();
                    assertThat(ex.getMessage()).contains("32 MiB");
                });
    }
}
