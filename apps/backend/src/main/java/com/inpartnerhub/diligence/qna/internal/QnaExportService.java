package com.inpartnerhub.diligence.qna.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.ingestion.PreviewRenderer;
import com.inpartnerhub.diligence.qna.QnaEvents.QnaExported;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
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

    record ExportFile(String fileName, String contentType, byte[] content) {
    }

    private final QuestionService questions;
    private final QnaAccess access;
    private final PreviewRenderer renderer;
    private final ApplicationEventPublisher events;
    private final ZoneId zone;

    QnaExportService(QuestionService questions, QnaAccess access, PreviewRenderer renderer,
            ApplicationEventPublisher events, @Value("${diligence.request.reminder-zone:Asia/Jakarta}") ZoneId zone) {
        this.questions = questions;
        this.access = access;
        this.renderer = renderer;
        this.events = events;
        this.zone = zone;
    }

    ExportFile export(UUID projectId, QuestionService.QuestionFilter filter, Format format) {
        var context = access.project(projectId);
        var list = questions.list(projectId, filter);
        var answers = questions.answers(list);
        var xlsx = workbook(context.code(), list, answers);
        var base = "qna-" + context.code().toLowerCase(Locale.ROOT) + "-" + Instant.now().atZone(zone).toLocalDate();
        events.publishEvent(new QnaExported(projectId, format.name(), list.size(), RequestMetadata.capture()));
        return switch (format) {
            case XLSX -> new ExportFile(base + ".xlsx", XLSX, xlsx);
            case PDF -> new ExportFile(base + ".pdf", "application/pdf", pdf(xlsx, base + ".xlsx"));
        };
    }

    private byte[] workbook(String projectCode, List<Question> list, Map<UUID, Answer> answers) {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
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
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Q&A export failed", ex);
        }
    }

    private String time(Instant at) {
        return at == null ? "" : TIMESTAMP.format(at.atZone(zone));
    }

    private byte[] pdf(byte[] xlsx, String fileName) {
        try {
            var target = Files.createTempFile("qna", ".pdf");
            try {
                renderer.renderPdf(new ByteArrayInputStream(xlsx), fileName, XLSX, target);
                return Files.readAllBytes(target);
            } finally {
                Files.deleteIfExists(target);
            }
        } catch (IOException | RuntimeException ex) {
            throw new UpstreamServiceException("The PDF export is not available right now", ex);
        }
    }
}
