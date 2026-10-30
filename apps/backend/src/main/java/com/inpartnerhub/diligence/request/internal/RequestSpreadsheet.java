package com.inpartnerhub.diligence.request.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.inpartnerhub.diligence.shared.web.InvalidRequestException;

/**
 * The request list as an Excel workbook (PRD REQ-01, REQ-05). The first sheet has one header row followed by one row
 * per item, so an exported list can be edited and imported again. Headers are matched by name (English or
 * Indonesian), the column order does not matter, and export-only columns are ignored on import.
 */
final class RequestSpreadsheet {

    static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final int MAX_ROWS = 2000;

    /** Import columns: header name → accepted aliases (lower case). */
    enum Column {
        NUMBER("Number", "number", "no", "no.", "nomor"),
        WORKSTREAM("Workstream", "workstream", "bidang"),
        CATEGORY("Category", "category", "kategori"),
        DESCRIPTION("Description", "description", "deskripsi", "permintaan"),
        PRIORITY("Priority", "priority", "prioritas"),
        DUE_DATE("Due date", "due date", "due", "tenggat", "batas waktu"),
        WAVE("Wave", "wave", "gelombang");

        final String header;
        final List<String> aliases;

        Column(String header, String... aliases) {
            this.header = header;
            this.aliases = List.of(aliases);
        }

        static Optional<Column> forHeader(String text) {
            var key = text.strip().toLowerCase(Locale.ROOT);
            for (var column : values()) {
                if (column.aliases.contains(key)) {
                    return Optional.of(column);
                }
            }
            return Optional.empty();
        }
    }

    /** One item as exported. */
    record ExportRow(String number, String workstream, String category, String description, String priority,
            LocalDate dueDate, int wave, String status, String assignee, long documents, Instant requestedAt,
            Instant updatedAt) {
    }

    /** One data row as read; values are raw text except the parsed due date. */
    record ImportRow(int rowNumber, Map<Column, String> values, LocalDate dueDate, String dueDateError) {

        String get(Column column) {
            var value = values.get(column);
            return value == null || value.isBlank() ? null : value.strip();
        }
    }

    private static final String[] EXPORT_EXTRA = { "Status", "Assignee", "Documents", "Requested at", "Last update" };
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(DateTimeFormatter.ISO_LOCAL_DATE,
            strict("d/M/uuuu"), strict("d-M-uuuu"), strict("d.M.uuuu"));

    private RequestSpreadsheet() {
    }

    // ------------------------------------------------------------------ export

    static byte[] export(String projectCode, String projectName, List<ExportRow> rows, ZoneId zone) {
        try (var workbook = new XSSFWorkbook()) {
            var styles = new Styles(workbook);
            var sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName(projectCode));
            var headers = new ArrayList<String>();
            for (var column : Column.values()) {
                headers.add(column.header);
            }
            headers.addAll(List.of(EXPORT_EXTRA));
            header(sheet, headers, styles);
            var r = 1;
            for (var row : rows) {
                var line = sheet.createRow(r++);
                text(line, 0, row.number(), styles.cell);
                text(line, 1, row.workstream(), styles.cell);
                text(line, 2, row.category(), styles.cell);
                text(line, 3, row.description(), styles.wrapped);
                text(line, 4, row.priority(), styles.cell);
                date(line, 5, row.dueDate(), styles.date);
                number(line, 6, row.wave(), styles.cell);
                text(line, 7, row.status(), styles.cell);
                text(line, 8, row.assignee(), styles.cell);
                number(line, 9, row.documents(), styles.cell);
                text(line, 10, row.requestedAt() == null ? null : TIMESTAMP.format(row.requestedAt().atZone(zone)),
                        styles.cell);
                text(line, 11, row.updatedAt() == null ? null : TIMESTAMP.format(row.updatedAt().atZone(zone)),
                        styles.cell);
            }
            layout(sheet, new int[] { 12, 16, 22, 60, 10, 12, 7, 15, 22, 11, 17, 17 }, headers.size(), rows.size());
            sheet.getHeader().setLeft(projectCode + " · " + projectName);
            sheet.getFooter().setRight("&P / &N");
            return bytes(workbook);
        } catch (IOException ex) {
            throw new IllegalStateException("Excel export failed", ex);
        }
    }

    /** An empty import sheet with drop-downs for the project's workstreams and the priorities, plus instructions. */
    static byte[] template(List<String> workstreams) {
        try (var workbook = new XSSFWorkbook()) {
            var styles = new Styles(workbook);
            var sheet = workbook.createSheet("Request list");
            var headers = new ArrayList<String>();
            for (var column : Column.values()) {
                headers.add(column.header);
            }
            header(sheet, headers, styles);
            var validation = sheet.getDataValidationHelper();
            if (!workstreams.isEmpty()) {
                sheet.addValidationData(validation.createValidation(
                        validation.createExplicitListConstraint(workstreams.toArray(String[]::new)),
                        new CellRangeAddressList(1, MAX_ROWS, Column.WORKSTREAM.ordinal(),
                                Column.WORKSTREAM.ordinal())));
            }
            sheet.addValidationData(validation.createValidation(
                    validation.createExplicitListConstraint(new String[] { "LOW", "MEDIUM", "HIGH" }),
                    new CellRangeAddressList(1, MAX_ROWS, Column.PRIORITY.ordinal(), Column.PRIORITY.ordinal())));
            layout(sheet, new int[] { 12, 16, 22, 60, 10, 12, 7 }, headers.size(), 0);

            var help = workbook.createSheet("Instructions");
            var lines = List.of(
                    "Number: optional. Empty = next number of the workstream (e.g. LEG-004). "
                            + "An existing number updates that item.",
                    "Workstream: " + String.join(", ", workstreams) + " (or the number prefix, e.g. LEG).",
                    "Description: required, at most 2000 characters. Category: optional, at most 200 characters.",
                    "Priority: LOW, MEDIUM (default) or HIGH. Due date: a date cell or YYYY-MM-DD.",
                    "Wave: 1-20 (default 1). Items are released to the target per wave.",
                    "Nomor: opsional. Kosong = nomor berikutnya per workstream. Nomor yang sudah ada memperbarui item.",
                    "Deskripsi wajib diisi. Prioritas: LOW, MEDIUM, HIGH. Tenggat: tanggal atau YYYY-MM-DD.",
                    "Gelombang (wave): 1-20; item dirilis ke pihak target per gelombang.");
            for (var i = 0; i < lines.size(); i++) {
                help.createRow(i).createCell(0).setCellValue(lines.get(i));
            }
            help.setColumnWidth(0, 120 * 256);
            return bytes(workbook);
        } catch (IOException ex) {
            throw new IllegalStateException("Excel template failed", ex);
        }
    }

    // ------------------------------------------------------------------ import

    /** Reads the first sheet. Rows without any value are skipped. */
    static List<ImportRow> read(InputStream content) {
        try (Workbook workbook = new XSSFWorkbook(content)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new InvalidRequestException("The workbook has no sheet");
            }
            var sheet = workbook.getSheetAt(0);
            var headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw new InvalidRequestException("The first sheet is empty");
            }
            var formatter = new DataFormatter(Locale.ROOT);
            var columns = new HashMap<Integer, Column>();
            for (var cell : headerRow) {
                Column.forHeader(formatter.formatCellValue(cell)).ifPresent(c -> columns.putIfAbsent(cell
                        .getColumnIndex(), c));
            }
            if (!columns.containsValue(Column.WORKSTREAM) || !columns.containsValue(Column.DESCRIPTION)) {
                throw new InvalidRequestException("The first row must contain the column headers, at least "
                        + "'Workstream' and 'Description'");
            }
            var rows = new ArrayList<ImportRow>();
            for (var r = headerRow.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                var row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                var values = new HashMap<Column, String>();
                LocalDate due = null;
                String dueError = null;
                for (var entry : columns.entrySet()) {
                    var cell = row.getCell(entry.getKey());
                    if (cell == null) {
                        continue;
                    }
                    if (entry.getValue() == Column.DUE_DATE) {
                        var text = formatter.formatCellValue(cell).strip();
                        values.put(Column.DUE_DATE, text);
                        if (!text.isEmpty()) {
                            var parsed = date(cell, text);
                            due = parsed.orElse(null);
                            dueError = parsed.isPresent() ? null : "Invalid due date '" + text + "'";
                        }
                    } else {
                        values.put(entry.getValue(), formatter.formatCellValue(cell));
                    }
                }
                if (values.values().stream().allMatch(v -> v == null || v.isBlank())) {
                    continue;
                }
                if (rows.size() >= MAX_ROWS) {
                    throw new InvalidRequestException("At most " + MAX_ROWS + " rows can be imported at once");
                }
                rows.add(new ImportRow(r + 1, values, due, dueError));
            }
            return rows;
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof InvalidRequestException invalid) {
                throw invalid;
            }
            throw new InvalidRequestException("The file is not a readable Excel workbook (.xlsx)");
        }
    }

    static Optional<LocalDate> date(Cell cell, String text) {
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return Optional.of(cell.getLocalDateTimeCellValue().toLocalDate());
        }
        for (var format : DATE_FORMATS) {
            try {
                return Optional.of(LocalDate.parse(text, format));
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ helpers

    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT);
    }

    private static void header(Sheet sheet, List<String> headers, Styles styles) {
        var row = sheet.createRow(0);
        for (var i = 0; i < headers.size(); i++) {
            text(row, i, headers.get(i), styles.header);
        }
    }

    /** Column widths, frozen header, filter and a landscape print layout fitted to the page width (PDF export). */
    private static void layout(Sheet sheet, int[] widths, int columns, int rows) {
        for (var i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i] * 256);
        }
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(rows, 1), 0, columns - 1));
        sheet.setRepeatingRows(new CellRangeAddress(0, 0, 0, columns - 1));
        sheet.setFitToPage(true);
        var print = sheet.getPrintSetup();
        print.setLandscape(true);
        print.setPaperSize(PrintSetup.A4_PAPERSIZE);
        print.setFitWidth((short) 1);
        print.setFitHeight((short) 0);
    }

    private static void text(Row row, int column, String value, CellStyle style) {
        var cell = row.createCell(column);
        cell.setCellStyle(style);
        if (value != null) {
            cell.setCellValue(value);
        }
    }

    private static void number(Row row, int column, long value, CellStyle style) {
        var cell = row.createCell(column);
        cell.setCellStyle(style);
        cell.setCellValue(value);
    }

    private static void date(Row row, int column, LocalDate value, CellStyle style) {
        var cell = row.createCell(column);
        cell.setCellStyle(style);
        if (value != null) {
            cell.setCellValue(value);
        }
    }

    private static byte[] bytes(Workbook workbook) throws IOException {
        var out = new ByteArrayOutputStream();
        workbook.write(out);
        return out.toByteArray();
    }

    private static final class Styles {

        final CellStyle header;
        final CellStyle cell;
        final CellStyle wrapped;
        final CellStyle date;

        Styles(Workbook workbook) {
            var bold = workbook.createFont();
            bold.setBold(true);
            header = workbook.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            borders(header);
            cell = workbook.createCellStyle();
            cell.setVerticalAlignment(VerticalAlignment.TOP);
            borders(cell);
            wrapped = workbook.createCellStyle();
            wrapped.cloneStyleFrom(cell);
            wrapped.setWrapText(true);
            date = workbook.createCellStyle();
            date.cloneStyleFrom(cell);
            date.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
        }

        private static void borders(CellStyle style) {
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
        }
    }
}
