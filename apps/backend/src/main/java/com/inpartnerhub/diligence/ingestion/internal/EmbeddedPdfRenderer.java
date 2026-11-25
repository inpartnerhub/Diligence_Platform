package com.inpartnerhub.diligence.ingestion.internal;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBrType;

import com.inpartnerhub.diligence.ingestion.ScanException;

/**
 * In-process PDF renderer using Apache POI and PDFBox when an external Gotenberg server is not configured
 * ({@code GOTENBERG_URL} is empty). Renders DOCX, XLSX, PPTX, TXT, and CSV files to A4 PDFs so the standard
 * {@link PageRasterizer} preview pipeline works without external sidecars.
 */
final class EmbeddedPdfRenderer {

    static final int MAX_INPUT_BYTES = 32 * 1024 * 1024;

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final String TXT = "text/plain";
    private static final String CSV = "text/csv";

    private static final Set<String> SUPPORTED_TYPES = Set.of(DOCX, XLSX, PPTX, TXT, CSV);

    private static final PDRectangle A4_PORTRAIT = PDRectangle.A4;
    private static final PDRectangle A4_LANDSCAPE = new PDRectangle(PDRectangle.A4.getHeight(),
            PDRectangle.A4.getWidth());

    private static final float MARGIN = 40f;
    private static final float LINE_LEADING = 1.35f;
    private static final float CELL_PADDING = 4f;
    private static final Color HEADING_COLOR = new Color(0x1F, 0x38, 0x64);
    private static final Color HEADER_FILL = new Color(0xE1, 0xE4, 0xE8);
    private static final Color BORDER_COLOR = new Color(0xBD, 0xC3, 0xC7);

    static {
        if (ZipSecureFile.getMinInflateRatio() < 0.01) {
            ZipSecureFile.setMinInflateRatio(0.01);
        }
        if (ZipSecureFile.getMaxEntrySize() > 32L * 1024 * 1024) {
            ZipSecureFile.setMaxEntrySize(32L * 1024 * 1024);
        }
        if (ZipSecureFile.getMaxTextSize() > 10L * 1024 * 1024) {
            ZipSecureFile.setMaxTextSize(10L * 1024 * 1024);
        }
    }

    private EmbeddedPdfRenderer() {
    }

    static boolean supports(String contentType) {
        return contentType != null && SUPPORTED_TYPES.contains(IngestionProperties.baseType(contentType));
    }

    static void renderPdf(InputStream content, String contentType, Path target) {
        if (!supports(contentType)) {
            throw new ScanException("Unsupported content type for embedded PDF rendering: " + contentType, false,
                    null);
        }
        var baseType = IngestionProperties.baseType(contentType);
        try {
            var bytes = readBounded(content);
            var pageSize = (XLSX.equals(baseType) || PPTX.equals(baseType)) ? A4_LANDSCAPE : A4_PORTRAIT;
            try (var document = new PDDocument(IOUtils.createTempFileOnlyStreamCache());
                    var writer = new PdfPageWriter(document, pageSize)) {
                switch (baseType) {
                    case DOCX -> renderDocx(bytes, writer);
                    case XLSX -> renderXlsx(bytes, writer);
                    case PPTX -> renderPptx(bytes, writer);
                    case TXT, CSV -> renderText(bytes, writer);
                    default -> throw new ScanException("Unsupported content type: " + contentType, false, null);
                }
                if (document.getNumberOfPages() == 0) {
                    writer.newPage();
                }
                writer.finish();
                document.save(target.toFile());
            }
        } catch (ScanException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new ScanException("Embedded PDF rendering failed: " + ex.getMessage(), false, ex);
        }
    }

    private static byte[] readBounded(InputStream content) throws IOException {
        if (content == null) {
            throw new ScanException("Embedded PDF rendering failed: content stream is null", false, null);
        }
        var out = new ByteArrayOutputStream();
        var buf = new byte[8192];
        var total = 0;
        int read;
        while ((read = content.read(buf)) != -1) {
            total += read;
            if (total > MAX_INPUT_BYTES) {
                throw new ScanException("File exceeds 32 MiB embedded rendering limit", false, null);
            }
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ DOCX

    private static void renderDocx(byte[] bytes, PdfPageWriter writer) throws IOException {
        try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            writer.newPage();
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    renderDocxParagraph(paragraph, writer);
                } else if (element instanceof XWPFTable table) {
                    renderDocxTable(table, writer);
                }
            }
        }
    }

    private static void renderDocxParagraph(XWPFParagraph paragraph, PdfPageWriter writer) throws IOException {
        if (paragraph.isPageBreak()) {
            writer.newPage();
        }
        var runs = paragraph.getRuns();
        var maxFontSize = 0d;
        var hasNonBlankRun = false;
        var allNonBlankBold = true;
        Color paragraphColor = null;
        var pendingText = new StringBuilder();

        for (XWPFRun run : runs) {
            var hasPageBreak = false;
            for (var br : run.getCTR().getBrList()) {
                if (STBrType.PAGE.equals(br.getType())) {
                    hasPageBreak = true;
                    break;
                }
            }
            if (hasPageBreak) {
                if (!pendingText.isEmpty()) {
                    emitDocxText(paragraph, pendingText.toString(), maxFontSize, hasNonBlankRun && allNonBlankBold,
                            paragraphColor, writer);
                    pendingText.setLength(0);
                }
                writer.newPage();
            }
            var text = run.text();
            if (text != null && !text.isEmpty()) {
                pendingText.append(text);
                if (!text.isBlank()) {
                    hasNonBlankRun = true;
                    if (!run.isBold()) {
                        allNonBlankBold = false;
                    }
                    if (paragraphColor == null) {
                        paragraphColor = parseHexColor(run.getColor());
                    }
                }
                var size = run.getFontSizeAsDouble();
                if (size != null && size > maxFontSize) {
                    maxFontSize = size;
                }
            }
        }

        var text = !pendingText.isEmpty() ? pendingText.toString() : paragraph.getText();
        if (text == null || text.isEmpty()) {
            if (!paragraph.isPageBreak() && runs.isEmpty()) {
                writer.advanceBlank(6f);
            }
            return;
        }
        emitDocxText(paragraph, text, maxFontSize, hasNonBlankRun && allNonBlankBold, paragraphColor, writer);
    }

    private static void emitDocxText(XWPFParagraph paragraph, String text, double maxRunSize, boolean bold,
            Color runColor, PdfPageWriter writer) throws IOException {
        var style = paragraph.getStyle();
        var isHeadingStyle = style != null && (style.toLowerCase(Locale.ROOT).startsWith("heading")
                || style.toLowerCase(Locale.ROOT).equals("title"));
        var fontSize = (maxRunSize >= 15d || isHeadingStyle) ? 16f : 10f;
        var isBold = bold || isHeadingStyle;
        var color = runColor != null ? runColor : (isHeadingStyle ? HEADING_COLOR : Color.BLACK);
        var align = toAlignment(paragraph.getAlignment());
        var beforeTwips = paragraph.getSpacingBefore();
        var afterTwips = paragraph.getSpacingAfter();
        var spaceBefore = beforeTwips > 0 ? Math.min(beforeTwips / 20f, 120f) : (fontSize >= 16f ? 8f : 1f);
        var spaceAfter = afterTwips > 0 ? Math.min(afterTwips / 20f, 20f) : 4f;
        writer.drawParagraph(text, fontSize, isBold, align, spaceBefore, spaceAfter, color);
    }

    private static void renderDocxTable(XWPFTable table, PdfPageWriter writer) throws IOException {
        var rows = table.getRows();
        if (rows.isEmpty()) {
            return;
        }
        var colCount = 0;
        for (var row : rows) {
            colCount = Math.max(colCount, row.getTableCells().size());
        }
        if (colCount == 0) {
            return;
        }
        var widths = computeDocxColumnWidths(table, colCount, writer.usableWidth());
        var fontSize = colCount >= 6 ? 8.5f : 9.5f;

        for (var row : rows) {
            var cells = row.getTableCells();
            var spec = new ArrayList<TableCellSpec>(colCount);
            for (var c = 0; c < colCount; c++) {
                XWPFTableCell cell = c < cells.size() ? cells.get(c) : null;
                var cellText = cell == null ? "" : extractCellText(cell);
                var fill = cell == null ? null : parseHexColor(cell.getColor());
                var isBold = cell != null && isCellBold(cell);
                var align = cell == null ? Alignment.LEFT : cellAlignment(cell);
                var fg = fill != null && isDark(fill) ? Color.WHITE : Color.BLACK;
                spec.add(new TableCellSpec(cellText, widths[c], fill, fg, isBold, align));
            }
            writer.drawTableRow(spec, fontSize, null);
        }
        writer.advanceBlank(8f);
    }

    private static float[] computeDocxColumnWidths(XWPFTable table, int colCount, float usableWidth) {
        var widths = new float[colCount];
        var ctTbl = table.getCTTbl();
        if (ctTbl != null && ctTbl.getTblGrid() != null) {
            var gridCols = ctTbl.getTblGrid().getGridColList();
            if (gridCols.size() == colCount) {
                var sum = 0d;
                var raw = new double[colCount];
                for (var i = 0; i < colCount; i++) {
                    var w = gridCols.get(i).getW();
                    var v = w == null ? 0d : ((Number) w).doubleValue();
                    if (v <= 0d) {
                        sum = 0d;
                        break;
                    }
                    raw[i] = v;
                    sum += v;
                }
                if (sum > 0d) {
                    return normalizeWidths(raw, usableWidth, 36f);
                }
            }
        }
        var equal = usableWidth / colCount;
        for (var i = 0; i < colCount; i++) {
            widths[i] = equal;
        }
        return widths;
    }

    private static String extractCellText(XWPFTableCell cell) {
        var sb = new StringBuilder();
        var paragraphs = cell.getParagraphs();
        for (var i = 0; i < paragraphs.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            var t = paragraphs.get(i).getText();
            if (t != null) {
                sb.append(t);
            }
        }
        return sb.toString();
    }

    private static boolean isCellBold(XWPFTableCell cell) {
        for (var p : cell.getParagraphs()) {
            for (var r : p.getRuns()) {
                var t = r.text();
                if (t != null && !t.isBlank() && r.isBold()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Alignment cellAlignment(XWPFTableCell cell) {
        var paragraphs = cell.getParagraphs();
        if (paragraphs.isEmpty()) {
            return Alignment.LEFT;
        }
        return toAlignment(paragraphs.getFirst().getAlignment());
    }

    // ------------------------------------------------------------------ XLSX

    private static void renderXlsx(byte[] bytes, PdfPageWriter writer) throws IOException {
        var formatter = new DataFormatter(Locale.ENGLISH);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheetCount = workbook.getNumberOfSheets();
            for (var s = 0; s < sheetCount; s++) {
                var sheet = workbook.getSheetAt(s);
                renderSheet(sheet, formatter, writer);
            }
        }
    }

    private static void renderSheet(Sheet sheet, DataFormatter formatter, PdfPageWriter writer) throws IOException {
        writer.newPage();
        writer.drawParagraph(sheet.getSheetName(), 13f, true, Alignment.LEFT, 0f, 8f, HEADING_COLOR);

        var lastRow = sheet.getLastRowNum();
        if (lastRow < 0) {
            writer.drawParagraph("(empty sheet)", 9.5f, false, Alignment.LEFT, 0f, 4f, Color.DARK_GRAY);
            return;
        }

        var rows = new ArrayList<List<String>>();
        var maxCols = 0;
        for (var r = 0; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            var lastCell = Math.min(Math.max((int) row.getLastCellNum(), 0), 24);
            var rowValues = new ArrayList<String>(lastCell);
            var nonEmptyEnd = 0;
            for (var c = 0; c < lastCell; c++) {
                var cell = row.getCell(c);
                var val = cell == null ? "" : formatter.formatCellValue(cell);
                rowValues.add(val);
                if (!val.isBlank()) {
                    nonEmptyEnd = c + 1;
                }
            }
            if (nonEmptyEnd > 0) {
                maxCols = Math.max(maxCols, nonEmptyEnd);
                rows.add(rowValues);
            }
        }

        if (rows.isEmpty() || maxCols == 0) {
            writer.drawParagraph("(empty sheet)", 9.5f, false, Alignment.LEFT, 0f, 4f, Color.DARK_GRAY);
            return;
        }

        var widths = computeXlsxColumnWidths(sheet, maxCols, writer.usableWidth());
        var fontSize = maxCols > 12 ? 7.5f : (maxCols > 8 ? 8.5f : 9f);
        var headerValues = rows.getFirst();
        var headerSpec = buildXlsxRowSpec(headerValues, widths, true);
        writer.drawTableRow(headerSpec, fontSize, null);

        for (var r = 1; r < rows.size(); r++) {
            var rowSpec = buildXlsxRowSpec(rows.get(r), widths, false);
            writer.drawTableRow(rowSpec, fontSize, () -> {
                writer.drawParagraph(sheet.getSheetName() + " (cont.)", 11f, true, Alignment.LEFT, 0f, 6f,
                        HEADING_COLOR);
                writer.drawTableRow(headerSpec, fontSize, null);
            });
        }
    }

    private static List<TableCellSpec> buildXlsxRowSpec(List<String> values, float[] widths, boolean isHeader) {
        var specs = new ArrayList<TableCellSpec>(widths.length);
        for (var c = 0; c < widths.length; c++) {
            var val = c < values.size() ? values.get(c) : "";
            specs.add(new TableCellSpec(val, widths[c], isHeader ? HEADER_FILL : null, Color.BLACK, isHeader,
                    Alignment.LEFT));
        }
        return specs;
    }

    private static float[] computeXlsxColumnWidths(Sheet sheet, int colCount, float usableWidth) {
        var raw = new double[colCount];
        for (var c = 0; c < colCount; c++) {
            var w = sheet.getColumnWidth(c) / 256d;
            raw[c] = Math.clamp(w, 6d, 65d);
        }
        return normalizeWidths(raw, usableWidth, 30f);
    }

    // ------------------------------------------------------------------ PPTX

    private static void renderPptx(byte[] bytes, PdfPageWriter writer) throws IOException {
        try (var ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            var slides = ppt.getSlides();
            for (var i = 0; i < slides.size(); i++) {
                writer.newPage();
                writer.drawParagraph("Slide " + (i + 1), 10f, true, Alignment.LEFT, 0f, 8f, Color.GRAY);
                var slide = slides.get(i);
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        var text = textShape.getText();
                        if (text == null || text.isBlank()) {
                            continue;
                        }
                        var placeholder = textShape.getPlaceholder();
                        var isTitle = placeholder == Placeholder.TITLE || placeholder == Placeholder.CENTERED_TITLE;
                        writer.drawParagraph(text, isTitle ? 16f : 11f, isTitle,
                                isTitle ? Alignment.CENTER : Alignment.LEFT, isTitle ? 4f : 2f, isTitle ? 10f : 6f,
                                isTitle ? HEADING_COLOR : Color.BLACK);
                    } else if (shape instanceof XSLFTable table) {
                        renderPptxTable(table, writer);
                    }
                }
            }
        }
    }

    private static void renderPptxTable(XSLFTable table, PdfPageWriter writer) throws IOException {
        var rows = table.getRows();
        if (rows.isEmpty()) {
            return;
        }
        var colCount = 0;
        for (var row : rows) {
            colCount = Math.max(colCount, row.getCells().size());
        }
        if (colCount == 0) {
            return;
        }
        var width = writer.usableWidth() / colCount;
        for (var r = 0; r < rows.size(); r++) {
            var cells = rows.get(r).getCells();
            var spec = new ArrayList<TableCellSpec>(colCount);
            for (var c = 0; c < colCount; c++) {
                var val = c < cells.size() ? cells.get(c).getText() : "";
                spec.add(new TableCellSpec(val == null ? "" : val, width, r == 0 ? HEADER_FILL : null, Color.BLACK,
                        r == 0, Alignment.LEFT));
            }
            writer.drawTableRow(spec, 9f, null);
        }
        writer.advanceBlank(6f);
    }

    // ------------------------------------------------------------------ TXT / CSV

    private static void renderText(byte[] bytes, PdfPageWriter writer) throws IOException {
        writer.newPage();
        var text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        if (text.isEmpty()) {
            return;
        }
        var lines = text.split("\\R", -1);
        for (var line : lines) {
            if (line.isEmpty()) {
                writer.advanceBlank(10f);
            } else {
                writer.drawParagraph(line, 10f, false, Alignment.LEFT, 0f, 1f, Color.BLACK);
            }
        }
    }

    // ------------------------------------------------------------------ WinAnsi & helpers

    static String sanitizeForWinAnsi(PDType1Font font, String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        var pre = new StringBuilder(raw.length());
        for (var i = 0; i < raw.length(); ) {
            var cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            switch (cp) {
                case '\r', '\n' -> {
                    // line breaks are handled by line splitting before drawing
                }
                case '\t' -> pre.append("    ");
                case '\u00A0' -> pre.append(' ');
                case '\u200B', '\u200C', '\u200D', '\uFEFF' -> {
                    // strip zero-width characters
                }
                case '\u2264' -> pre.append("<=");
                case '\u2265' -> pre.append(">=");
                case '\u2260' -> pre.append("!=");
                case '\u2248' -> pre.append("~=");
                case '\u2022' -> pre.append('*');
                case '\u2026' -> pre.append("...");
                case '\u2192' -> pre.append("->");
                case '\u2190' -> pre.append("<-");
                case '\u2713', '\u2714' -> pre.append('v');
                default -> pre.appendCodePoint(cp);
            }
        }
        var result = new StringBuilder(pre.length());
        for (var i = 0; i < pre.length(); ) {
            var cp = pre.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20 || cp == 0x7F) {
                continue;
            }
            var ch = new String(Character.toChars(cp));
            if (isEncodable(font, ch)) {
                result.append(ch);
            } else {
                result.append('?');
            }
        }
        return result.toString();
    }

    private static boolean isEncodable(PDType1Font font, String ch) {
        try {
            font.encode(ch);
            return true;
        } catch (IllegalArgumentException | IOException ex) {
            return false;
        }
    }

    private static float[] normalizeWidths(double[] raw, float totalWidth, float minWidth) {
        var n = raw.length;
        var widths = new float[n];
        var minTotal = minWidth * n;
        if (minTotal >= totalWidth) {
            var eq = totalWidth / n;
            for (var i = 0; i < n; i++) {
                widths[i] = eq;
            }
            return widths;
        }
        var sum = 0d;
        for (var v : raw) {
            sum += v;
        }
        var distributable = totalWidth - minTotal;
        for (var i = 0; i < n; i++) {
            widths[i] = minWidth + (float) (distributable * (raw[i] / sum));
        }
        return widths;
    }

    private static Color parseHexColor(String hex) {
        if (hex == null) {
            return null;
        }
        var clean = hex.startsWith("#") ? hex.substring(1) : hex;
        if (clean.length() != 6 || "auto".equalsIgnoreCase(clean)) {
            return null;
        }
        try {
            return new Color(Integer.parseInt(clean, 16));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean isDark(Color c) {
        var luminance = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
        return luminance < 180.0;
    }

    private static Alignment toAlignment(ParagraphAlignment align) {
        if (align == ParagraphAlignment.CENTER) {
            return Alignment.CENTER;
        }
        if (align == ParagraphAlignment.RIGHT) {
            return Alignment.RIGHT;
        }
        return Alignment.LEFT;
    }

    private enum Alignment {
        LEFT, CENTER, RIGHT
    }

    private record TableCellSpec(String text, float width, Color fill, Color textColor, boolean bold,
            Alignment alignment) {
    }

    @FunctionalInterface
    private interface PageHeaderCallback {
        void onNewPage() throws IOException;
    }

    private static final class PdfPageWriter implements AutoCloseable {

        private final PDDocument document;
        private final PDRectangle pageSize;
        private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private PDPageContentStream stream;
        private float y;

        PdfPageWriter(PDDocument document, PDRectangle pageSize) {
            this.document = document;
            this.pageSize = pageSize;
        }

        float usableWidth() {
            return pageSize.getWidth() - 2f * MARGIN;
        }

        void newPage() throws IOException {
            finish();
            var page = new PDPage(pageSize);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = pageSize.getHeight() - MARGIN;
        }

        void finish() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        void advanceBlank(float height) throws IOException {
            if (stream == null) {
                return;
            }
            if (y - height < MARGIN) {
                newPage();
            } else {
                y -= height;
            }
        }

        void drawParagraph(String text, float fontSize, boolean isBold, Alignment align, float spaceBefore,
                float spaceAfter, Color color) throws IOException {
            if (stream == null) {
                newPage();
            }
            var font = isBold ? bold : regular;
            var lineHeight = fontSize * LINE_LEADING;
            if (y < pageSize.getHeight() - MARGIN - 0.1f && spaceBefore > 0f) {
                advanceBlank(spaceBefore);
            }
            var rawLines = text.split("\\R", -1);
            for (var rawLine : rawLines) {
                var wrapped = wrapLines(font, fontSize, rawLine, usableWidth());
                for (var line : wrapped) {
                    if (y - lineHeight < MARGIN) {
                        newPage();
                    }
                    var width = textWidth(font, fontSize, line);
                    var x = switch (align) {
                        case CENTER -> MARGIN + Math.max(0f, (usableWidth() - width) / 2f);
                        case RIGHT -> MARGIN + Math.max(0f, usableWidth() - width);
                        case LEFT -> MARGIN;
                    };
                    drawTextLine(font, fontSize, color, x, y - fontSize, line);
                    y -= lineHeight;
                }
            }
            if (spaceAfter > 0f) {
                y = Math.max(MARGIN, y - spaceAfter);
            }
        }

        void drawTableRow(List<TableCellSpec> cells, float fontSize, PageHeaderCallback onPaginate)
                throws IOException {
            if (stream == null) {
                newPage();
            }
            var lineHeight = fontSize * LINE_LEADING;
            var maxCellHeight = pageSize.getHeight() - 2f * MARGIN - 40f;
            var maxLinesPerCell = Math.max(1, (int) Math.floor((maxCellHeight - 2f * CELL_PADDING) / lineHeight));

            var cellLines = new ArrayList<List<String>>(cells.size());
            var maxLines = 1;
            for (var cell : cells) {
                var font = cell.bold() ? bold : regular;
                var innerWidth = Math.max(10f, cell.width() - 2f * CELL_PADDING);
                var lines = new ArrayList<String>();
                for (var rawLine : cell.text().split("\\R", -1)) {
                    lines.addAll(wrapLines(font, fontSize, rawLine, innerWidth));
                }
                if (lines.isEmpty()) {
                    lines.add("");
                }
                if (lines.size() > maxLinesPerCell) {
                    lines = new ArrayList<>(lines.subList(0, maxLinesPerCell));
                    var last = lines.getLast();
                    lines.set(lines.size() - 1, truncateWithEllipsis(font, fontSize, last, innerWidth));
                }
                maxLines = Math.max(maxLines, lines.size());
                cellLines.add(lines);
            }

            var rowHeight = maxLines * lineHeight + 2f * CELL_PADDING;
            if (y - rowHeight < MARGIN) {
                newPage();
                if (onPaginate != null) {
                    onPaginate.onNewPage();
                }
            }

            var rowTop = y;
            var rowBottom = rowTop - rowHeight;
            var x = MARGIN;
            for (var i = 0; i < cells.size(); i++) {
                var cell = cells.get(i);
                var w = cell.width();
                if (cell.fill() != null) {
                    stream.setNonStrokingColor(cell.fill());
                    stream.addRect(x, rowBottom, w, rowHeight);
                    stream.fill();
                }
                stream.setStrokingColor(BORDER_COLOR);
                stream.setLineWidth(0.5f);
                stream.addRect(x, rowBottom, w, rowHeight);
                stream.stroke();

                var font = cell.bold() ? bold : regular;
                var lines = cellLines.get(i);
                var innerWidth = Math.max(10f, w - 2f * CELL_PADDING);
                var textY = rowTop - CELL_PADDING - fontSize;
                for (var line : lines) {
                    if (!line.isEmpty()) {
                        var lw = textWidth(font, fontSize, line);
                        var tx = switch (cell.alignment()) {
                            case CENTER -> x + CELL_PADDING + Math.max(0f, (innerWidth - lw) / 2f);
                            case RIGHT -> x + CELL_PADDING + Math.max(0f, innerWidth - lw);
                            case LEFT -> x + CELL_PADDING;
                        };
                        drawTextLine(font, fontSize, cell.textColor(), tx, textY, line);
                    }
                    textY -= lineHeight;
                }
                x += w;
            }
            y = rowBottom;
        }

        private void drawTextLine(PDType1Font font, float fontSize, Color color, float x, float baselineY,
                String line) throws IOException {
            if (line.isEmpty()) {
                return;
            }
            stream.setNonStrokingColor(color);
            stream.beginText();
            stream.setFont(font, fontSize);
            stream.newLineAtOffset(x, baselineY);
            stream.showText(line);
            stream.endText();
        }

        private List<String> wrapLines(PDType1Font font, float fontSize, String raw, float maxWidth)
                throws IOException {
            var safe = sanitizeForWinAnsi(font, raw);
            if (safe.isEmpty()) {
                return List.of("");
            }
            var lines = new ArrayList<String>();
            var words = safe.split("\\s+");
            var current = new StringBuilder();
            for (var word : words) {
                if (word.isEmpty()) {
                    continue;
                }
                if (current.isEmpty()) {
                    appendWordWithCharBreak(font, fontSize, word, maxWidth, current, lines);
                } else {
                    var candidate = current + " " + word;
                    if (textWidth(font, fontSize, candidate) <= maxWidth) {
                        current.append(' ').append(word);
                    } else {
                        lines.add(current.toString());
                        current.setLength(0);
                        appendWordWithCharBreak(font, fontSize, word, maxWidth, current, lines);
                    }
                }
            }
            if (!current.isEmpty()) {
                lines.add(current.toString());
            }
            return lines.isEmpty() ? List.of("") : lines;
        }

        private void appendWordWithCharBreak(PDType1Font font, float fontSize, String word, float maxWidth,
                StringBuilder current, List<String> lines) throws IOException {
            if (textWidth(font, fontSize, word) <= maxWidth) {
                current.append(word);
                return;
            }
            for (var i = 0; i < word.length(); i++) {
                var c = word.charAt(i);
                var candidate = current.toString() + c;
                if (!current.isEmpty() && textWidth(font, fontSize, candidate) > maxWidth) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                current.append(c);
            }
        }

        private String truncateWithEllipsis(PDType1Font font, float fontSize, String text, float maxWidth)
                throws IOException {
            var ellipsis = "...";
            if (textWidth(font, fontSize, text + ellipsis) <= maxWidth) {
                return text + ellipsis;
            }
            var end = text.length();
            while (end > 0 && textWidth(font, fontSize, text.substring(0, end) + ellipsis) > maxWidth) {
                end--;
            }
            return text.substring(0, end) + ellipsis;
        }

        private float textWidth(PDType1Font font, float fontSize, String text) throws IOException {
            return font.getStringWidth(text) / 1000f * fontSize;
        }

        @Override
        public void close() throws IOException {
            finish();
        }
    }
}
