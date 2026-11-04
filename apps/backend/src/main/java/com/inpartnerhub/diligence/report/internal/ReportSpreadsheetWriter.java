package com.inpartnerhub.diligence.report.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.inpartnerhub.diligence.finding.FindingDirectory.ReleasedFinding;
import com.inpartnerhub.diligence.finding.RiskLevel;
import com.inpartnerhub.diligence.project.WorkstreamType;

/**
 * The findings of one report version as an Excel workbook for client presentations (PRD RPT-04): a findings sheet
 * with one filterable row per finding and a summary sheet with the count per risk level.
 */
final class ReportSpreadsheetWriter {

    static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    record Content(String projectCode, String versionLabel, String language, Map<UUID, WorkstreamType> workstreams,
            List<ReleasedFinding> findings) {
    }

    private ReportSpreadsheetWriter() {
    }

    static byte[] write(Content content) {
        var text = ReportTexts.of(content.language());
        var english = "en".equals(content.language());
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var header = workbook.createCellStyle();
            var bold = workbook.createFont();
            bold.setBold(true);
            header.setFont(bold);
            var wrap = workbook.createCellStyle();
            wrap.setWrapText(true);

            var sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName(english ? "Findings" : "Temuan"));
            var headers = List.of("No.", "Workstream", english ? "Title" : "Judul",
                    text.category(), text.level(), text.impact(), english ? "Likelihood" : "Kemungkinan",
                    english ? "Score" : "Skor", text.monetary(), english ? "Currency" : "Mata uang", text.description(),
                    english ? "Impact description" : "Uraian dampak", text.recommendation(), text.sources());
            var top = sheet.createRow(0);
            for (var i = 0; i < headers.size(); i++) {
                text(top, i, headers.get(i), header);
            }
            var rowIndex = 1;
            for (var f : content.findings()) {
                var row = sheet.createRow(rowIndex++);
                var type = content.workstreams().get(f.workstreamId());
                text(row, 0, f.number(), null);
                text(row, 1, type == null ? null : text.workstreams().getOrDefault(type, type.name()), null);
                text(row, 2, f.title(), wrap);
                text(row, 3, f.category(), null);
                text(row, 4, f.riskLevel() == null ? text.unrated() : text.levels().get(f.riskLevel()), null);
                number(row, 5, f.impact());
                number(row, 6, f.likelihood());
                number(row, 7, f.riskScore());
                if (f.monetaryImpact() != null) {
                    row.createCell(8).setCellValue(f.monetaryImpact().doubleValue());
                }
                text(row, 9, f.currency(), null);
                text(row, 10, f.description(), wrap);
                text(row, 11, f.impactDescription(), wrap);
                text(row, 12, f.recommendation(), wrap);
                text(row, 13, f.sources().stream()
                        .map(s -> s.pageReference() == null ? s.documentName() : s.documentName() + ", " + s.pageReference())
                        .collect(Collectors.joining("; ")), wrap);
            }
            int[] widths = { 12, 16, 40, 18, 12, 8, 10, 8, 16, 9, 60, 40, 50, 40 };
            for (var i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, Math.max(rowIndex - 1, 1), 0, headers.size() - 1));
            landscape(sheet);

            var summary = workbook.createSheet(WorkbookUtil.createSafeSheetName(text.summary()));
            var title = summary.createRow(0);
            text(title, 0, content.projectCode() + " · " + text.version() + " " + content.versionLabel(), header);
            var counts = new EnumMap<RiskLevel, Long>(RiskLevel.class);
            content.findings().stream().filter(f -> f.riskLevel() != null)
                    .forEach(f -> counts.merge(f.riskLevel(), 1L, Long::sum));
            var head = summary.createRow(2);
            text(head, 0, text.level(), header);
            text(head, 1, text.count(), header);
            var r = 3;
            var levels = RiskLevel.values();
            for (var i = levels.length - 1; i >= 0; i--) {
                var row = summary.createRow(r++);
                text(row, 0, text.levels().get(levels[i]), null);
                row.createCell(1).setCellValue(counts.getOrDefault(levels[i], 0L));
            }
            var totalRow = summary.createRow(r);
            text(totalRow, 0, text.total(), header);
            totalRow.createCell(1).setCellValue(content.findings().size());
            summary.setColumnWidth(0, 30 * 256);
            summary.setColumnWidth(1, 12 * 256);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void landscape(Sheet sheet) {
        sheet.setFitToPage(true);
        var print = sheet.getPrintSetup();
        print.setLandscape(true);
        print.setPaperSize(PrintSetup.A4_PAPERSIZE);
        print.setFitWidth((short) 1);
        print.setFitHeight((short) 0);
    }

    private static void text(Row row, int column, String value, CellStyle style) {
        var cell = row.createCell(column);
        if (style != null) {
            cell.setCellStyle(style);
        }
        if (value != null) {
            cell.setCellValue(value);
        }
    }

    private static void number(Row row, int column, Integer value) {
        if (value != null) {
            row.createCell(column).setCellValue(value);
        }
    }
}
