package com.inpartnerhub.diligence.request.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.inpartnerhub.diligence.request.internal.RequestSpreadsheet.Column;
import com.inpartnerhub.diligence.request.internal.RequestSpreadsheet.ImportRow;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;

/**
 * Streams the first sheet of an imported request list (REQ-01) instead of building the whole workbook in memory
 * (#93). The upload is copied to a temporary file so the zip is read entry by entry, every entry is capped at
 * {@link #MAX_ENTRY_BYTES} once inflated, and reading stops at the first row beyond {@link RequestSpreadsheet#MAX_ROWS}.
 * Date-formatted cells are reported as ISO dates, which {@link RequestSpreadsheet#parseDate} accepts.
 */
final class RequestSheetReader {

    /** Largest inflated part (sheet XML, shared strings): far above a 2 000-row list, far below the heap. */
    static final long MAX_ENTRY_BYTES = 32L * 1024 * 1024;

    static {
        // POI keeps these limits globally; this is the only place the backend reads OOXML it did not write.
        if (ZipSecureFile.getMaxEntrySize() > MAX_ENTRY_BYTES) {
            ZipSecureFile.setMaxEntrySize(MAX_ENTRY_BYTES);
        }
    }

    private RequestSheetReader() {
    }

    static List<ImportRow> read(InputStream content) {
        java.nio.file.Path file = null;
        try {
            file = Files.createTempFile("request-import-", ".xlsx");
            Files.copy(content, file, StandardCopyOption.REPLACE_EXISTING);
            try (var pkg = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
                return parse(pkg);
            }
        } catch (InvalidRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex.getCause() instanceof InvalidRequestException invalid) {
                throw invalid;
            }
            throw new InvalidRequestException("The file is not a readable Excel workbook (.xlsx)");
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // the temporary directory is cleaned on restart
                }
            }
        }
    }

    private static List<ImportRow> parse(OPCPackage pkg) throws Exception {
        var reader = new XSSFReader(pkg);
        var sheets = reader.getSheetsData();
        if (!sheets.hasNext()) {
            throw new InvalidRequestException("The workbook has no sheet");
        }
        var collector = new Collector();
        var handler = new XSSFSheetXMLHandler(reader.getStylesTable(), null, new ReadOnlySharedStringsTable(pkg),
                collector, new IsoDateFormatter(), false);
        try (InputStream sheet = sheets.next()) {
            var parser = XMLHelper.newXMLReader();
            parser.setContentHandler(handler);
            parser.parse(new InputSource(sheet));
        } catch (SAXException ex) {
            if (ex.getCause() instanceof InvalidRequestException invalid) {
                throw invalid;
            }
            throw ex;
        }
        return collector.finish();
    }

    /** Collects the header row and the data rows, with the same rules as before the streaming reader. */
    private static final class Collector implements SheetContentsHandler {

        private final Map<Integer, Column> columns = new HashMap<>();
        private final List<ImportRow> rows = new ArrayList<>();
        private Map<Integer, String> current;
        private boolean headerSeen;
        private int rowNumber;

        @Override
        public void startRow(int rowNum) {
            rowNumber = rowNum;
            current = new HashMap<>();
        }

        @Override
        public void cell(String cellReference, String formattedValue, Comment comment) {
            if (cellReference != null && formattedValue != null) {
                current.put((int) new CellReference(cellReference).getCol(), formattedValue);
            }
        }

        @Override
        public void endRow(int rowNum) {
            if (!headerSeen) {
                headerSeen = true;
                current.forEach((col, text) -> Column.forHeader(text).ifPresent(c -> columns.putIfAbsent(col, c)));
                if (!columns.containsValue(Column.WORKSTREAM) || !columns.containsValue(Column.DESCRIPTION)) {
                    throw new InvalidRequestException("The first row must contain the column headers, at least "
                            + "'Workstream' and 'Description'");
                }
                return;
            }
            var values = new HashMap<Column, String>();
            LocalDate due = null;
            String dueError = null;
            for (var entry : columns.entrySet()) {
                var text = current.get(entry.getKey());
                if (text == null) {
                    continue;
                }
                if (entry.getValue() == Column.DUE_DATE) {
                    var stripped = text.strip();
                    values.put(Column.DUE_DATE, stripped);
                    if (!stripped.isEmpty()) {
                        var parsed = RequestSpreadsheet.parseDate(stripped);
                        due = parsed.orElse(null);
                        dueError = parsed.isPresent() ? null : "Invalid due date '" + stripped + "'";
                    }
                } else {
                    values.put(entry.getValue(), text);
                }
            }
            if (values.values().stream().allMatch(v -> v == null || v.isBlank())) {
                return;
            }
            if (rows.size() >= RequestSpreadsheet.MAX_ROWS) {
                throw new InvalidRequestException("At most " + RequestSpreadsheet.MAX_ROWS
                        + " rows can be imported at once");
            }
            rows.add(new ImportRow(rowNumber + 1, values, due, dueError));
        }

        List<ImportRow> finish() {
            if (!headerSeen) {
                throw new InvalidRequestException("The first sheet is empty");
            }
            return rows;
        }
    }

    /** Formats date-formatted numeric cells as ISO dates, everything else as Excel shows it. */
    private static final class IsoDateFormatter extends DataFormatter {

        IsoDateFormatter() {
            super(Locale.ROOT);
        }

        @Override
        public String formatRawCellContents(double value, int formatIndex, String formatString) {
            return formatRawCellContents(value, formatIndex, formatString, false);
        }

        @Override
        public String formatRawCellContents(double value, int formatIndex, String formatString,
                boolean use1904Windowing) {
            if (DateUtil.isADateFormat(formatIndex, formatString) && DateUtil.isValidExcelDate(value)) {
                return DateTimeFormatter.ISO_LOCAL_DATE.format(
                        DateUtil.getLocalDateTime(value, use1904Windowing).toLocalDate());
            }
            return super.formatRawCellContents(value, formatIndex, formatString, use1904Windowing);
        }
    }
}
