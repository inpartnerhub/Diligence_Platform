package com.inpartnerhub.diligence.qna.internal;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import com.inpartnerhub.diligence.ingestion.PreviewRenderer;
import com.inpartnerhub.diligence.qna.QnaEvents.QnaExported;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.TemporaryFile;
import com.inpartnerhub.diligence.shared.web.UpstreamServiceException;

/**
 * The Q&A log as Excel or PDF (PRD QNA-03): the questions the caller can see with the answers they may read, so a
 * client's export never contains answers to questions that are still under review.
 */
@Service
@Transactional
class QnaExportService {

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    enum Format { XLSX, PDF }

    /** Rows of a streamed export kept in memory before they are flushed to disk (#129). */
    static final int ROW_WINDOW = 100;

    // The PDF export calls Gotenberg (up to the service timeout): it runs without a transaction and reads and audits
    // in a short one, so no pooled connection waits on the conversion (#141).
    private final TransactionOperations transactions;
    private final QuestionService questions;
    private final QnaAccess access;
    private final PreviewRenderer renderer;
    private final ApplicationEventPublisher events;
    private final ZoneId zone;

    QnaExportService(QuestionService questions, QnaAccess access, PreviewRenderer renderer,
            ApplicationEventPublisher events, @Value("${diligence.request.reminder-zone:Asia/Jakarta}") ZoneId zone,
            TransactionOperations transactions) {
        this.transactions = transactions;
        this.questions = questions;
        this.access = access;
        this.renderer = renderer;
        this.events = events;
        this.zone = zone;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    TemporaryFile export(UUID projectId, QuestionService.QuestionFilter filter, Format format) {
        var workbook = transactions.execute(status -> workbook(projectId, filter, format));
        if (format == Format.XLSX) {
            return workbook;
        }
        var base = workbook.fileName().substring(0, workbook.fileName().length() - ".xlsx".length());
        return new TemporaryFile(base + ".pdf", "application/pdf", pdf(workbook.path(), workbook.fileName()));
    }

    /** Reads the log, writes the workbook and audits the export, in the caller's (short) transaction. */
    private TemporaryFile workbook(UUID projectId, QuestionService.QuestionFilter filter, Format format) {
        var context = access.project(projectId);
        var list = questions.list(projectId, filter);
        var answers = questions.answers(list);
        var base = "qna-" + context.code().toLowerCase(Locale.ROOT) + "-" + Instant.now().atZone(zone).toLocalDate();
        // Written to temporary files, never held in memory as a whole (#129); the response deletes them once sent.
        var xlsx = TemporaryFile.create("qna", ".xlsx");
        try {
            try (var out = new BufferedOutputStream(Files.newOutputStream(xlsx))) {
                workbook(context.code(), list, answers, out);
            }
            events.publishEvent(new QnaExported(projectId, format.name(), list.size(), RequestMetadata.capture()));
            return new TemporaryFile(base + ".xlsx", XLSX, xlsx);
        } catch (IOException | RuntimeException ex) {
            TemporaryFile.delete(xlsx);
            throw ex instanceof IOException io ? new UncheckedIOException(io) : (RuntimeException) ex;
        }
    }

    /** Streaming workbook (#129): only a window of rows stays in memory. */
    private void workbook(String projectCode, List<Question> list, Map<UUID, Answer> answers,
            OutputStream out) {
        // close() also removes the workbook's temporary row files.
        try (var workbook = new SXSSFWorkbook(ROW_WINDOW)) {
            var sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName(projectCode + " Q&A"));
            var bold = workbook.createFont();
            bold.setBold(true);
            CellStyle header = workbook.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle wrapped = workbook.createCellStyle();
            wrapped.setWrapText(true);
            wrapped.setVerticalAlignment(VerticalAlignment.TOP);
            var titles = List.of("Number", "Status", "Question", "Sent", "Answer", "Answered", "Closed");
            var row = sheet.createRow(0);
            for (var i = 0; i < titles.size(); i++) {
                var cell = row.createCell(i);
                cell.setCellValue(titles.get(i));
                cell.setCellStyle(header);
            }
            var r = 1;
            for (var q : list) {
                var line = sheet.createRow(r++);
                var answer = answers.get(q.getId());
                var values = new String[] { q.getNumber(), q.getStatus().name(), q.getBody(), time(q.getSentAt()),
                        answer == null ? "" : answer.getBody(), answer == null ? "" : time(answer.getAnsweredAt()),
                        time(q.getClosedAt()) };
                for (var i = 0; i < values.length; i++) {
                    var cell = line.createCell(i);
                    cell.setCellValue(values[i]);
                    cell.setCellStyle(wrapped);
                }
            }
            var widths = new int[] { 10, 16, 60, 17, 60, 17, 17 };
            for (var i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, Math.max(list.size(), 1), 0, titles.size() - 1));
            sheet.setRepeatingRows(new CellRangeAddress(0, 0, 0, titles.size() - 1));
            sheet.setFitToPage(true);
            sheet.getPrintSetup().setLandscape(true);
            sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
            sheet.getPrintSetup().setFitWidth((short) 1);
            sheet.getPrintSetup().setFitHeight((short) 0);
            sheet.getHeader().setLeft(projectCode + " · Q&A");
            sheet.getFooter().setRight("&P / &N");
            workbook.write(out);
        } catch (IOException ex) {
            throw new IllegalStateException("Q&A export failed", ex);
        }
    }

    private String time(Instant at) {
        return at == null ? "" : TIMESTAMP.format(at.atZone(zone));
    }

    /** Converts the workbook file to PDF (Gotenberg) and deletes the workbook; returns the PDF file. */
    private Path pdf(Path xlsx, String fileName) {
        var target = TemporaryFile.create("qna", ".pdf");
        try (var content = Files.newInputStream(xlsx)) {
            renderer.renderPdf(content, fileName, XLSX, target);
            return target;
        } catch (IOException | RuntimeException ex) {
            TemporaryFile.delete(target);
            throw new UpstreamServiceException("The PDF export is not available right now", ex);
        } finally {
            TemporaryFile.delete(xlsx);
        }
    }
}
