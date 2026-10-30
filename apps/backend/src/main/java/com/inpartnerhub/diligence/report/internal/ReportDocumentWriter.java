package com.inpartnerhub.diligence.report.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.TableRowAlign;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;

import com.inpartnerhub.diligence.finding.FindingDirectory.ReleasedFinding;
import com.inpartnerhub.diligence.finding.RiskLevel;
import com.inpartnerhub.diligence.finding.RiskMatrix;
import com.inpartnerhub.diligence.project.WorkstreamType;

/**
 * The due diligence report as a Word document (PRD RPT-01; executive summary and heat map from RPT-02): cover,
 * executive summary, heat map in the configured thresholds, then one section per workstream with its released
 * findings by descending risk. Neutral layout; the firm's official template can replace it later (PRD Bab 15.2).
 */
final class ReportDocumentWriter {

    /** Appendix A colours, the same as the frontend's risk colours. */
    static final Map<RiskLevel, String> COLORS = new EnumMap<>(Map.of(RiskLevel.LOW, "389E0D",
            RiskLevel.MEDIUM, "D4B106", RiskLevel.HIGH, "D46B08", RiskLevel.CRITICAL, "CF1322"));

    record Content(String projectCode, String projectName, String targetName, String title, int versionNumber,
            String language, Instant generatedAt, ZoneId zone, Map<UUID, WorkstreamType> workstreams,
            List<ReleasedFinding> findings, RiskMatrix matrix) {
    }

    private ReportDocumentWriter() {
    }

    static byte[] write(Content content) {
        var text = Texts.of(content.language());
        try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            cover(document, content, text);
            summary(document, content, text);
            heatMap(document, content, text);
            findings(document, content, text);
            document.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("The report could not be written", ex);
        }
    }

    private static void cover(XWPFDocument document, Content content, Texts text) {
        var spacer = document.createParagraph();
        spacer.setSpacingBefore(2400);
        line(document, content.title(), 26, true, ParagraphAlignment.CENTER);
        line(document, content.projectCode() + " · " + content.projectName(), 14, false, ParagraphAlignment.CENTER);
        if (content.targetName() != null) {
            line(document, text.target + ": " + content.targetName(), 12, false, ParagraphAlignment.CENTER);
        }
        line(document, text.version + " " + content.versionNumber() + " · "
                + DateTimeFormatter.ofPattern("d MMMM yyyy", text.locale).format(content.generatedAt()
                        .atZone(content.zone())), 11, false, ParagraphAlignment.CENTER);
        line(document, text.confidential, 10, true, ParagraphAlignment.CENTER);
        pageBreak(document);
    }

    private static void summary(XWPFDocument document, Content content, Texts text) {
        heading(document, text.summary);
        var counts = new EnumMap<RiskLevel, Integer>(RiskLevel.class);
        for (var level : RiskLevel.values()) {
            counts.put(level, 0);
        }
        content.findings().stream().filter(f -> f.riskLevel() != null)
                .forEach(f -> counts.merge(f.riskLevel(), 1, Integer::sum));
        line(document, text.summaryIntro.formatted(content.findings().size()), 11, false, ParagraphAlignment.LEFT);
        var table = document.createTable(5, 2);
        table.setTableAlignment(TableRowAlign.LEFT);
        cell(table.getRow(0).getCell(0), text.level, true, null);
        cell(table.getRow(0).getCell(1), text.count, true, null);
        var row = 1;
        for (var level : List.of(RiskLevel.CRITICAL, RiskLevel.HIGH, RiskLevel.MEDIUM, RiskLevel.LOW)) {
            cell(table.getRow(row).getCell(0), text.levels.get(level), true, COLORS.get(level));
            cell(table.getRow(row).getCell(1), String.valueOf(counts.get(level)), false, null);
            row++;
        }
        var critical = content.findings().stream().filter(f -> f.riskLevel() == RiskLevel.CRITICAL
                || f.riskLevel() == RiskLevel.HIGH).toList();
        if (!critical.isEmpty()) {
            line(document, text.keyRisks, 11, true, ParagraphAlignment.LEFT);
            for (var f : critical) {
                line(document, "• " + f.number() + " " + f.title() + " (" + text.levels.get(f.riskLevel()) + ")", 11,
                        false, ParagraphAlignment.LEFT);
            }
        }
    }

    /** Rows: impact 5 → 1; columns: likelihood 1 → 5; each cell coloured by its level, with the finding count. */
    private static void heatMap(XWPFDocument document, Content content, Texts text) {
        heading(document, text.heatMap);
        var table = document.createTable(6, 6);
        cell(table.getRow(0).getCell(0), text.impactLikelihood, true, null);
        for (var likelihood = 1; likelihood <= 5; likelihood++) {
            cell(table.getRow(0).getCell(likelihood), String.valueOf(likelihood), true, null);
        }
        for (var impact = 5; impact >= 1; impact--) {
            var row = table.getRow(6 - impact);
            cell(row.getCell(0), String.valueOf(impact), true, null);
            for (var likelihood = 1; likelihood <= 5; likelihood++) {
                final var i = impact;
                final var l = likelihood;
                var count = content.findings().stream()
                        .filter(f -> f.impact() != null && f.impact() == i && f.likelihood() == l).count();
                var level = content.matrix().rate(impact, likelihood).level();
                cell(row.getCell(likelihood), count == 0 ? "" : String.valueOf(count), true, COLORS.get(level));
            }
        }
    }

    private static void findings(XWPFDocument document, Content content, Texts text) {
        var byWorkstream = new LinkedHashMap<WorkstreamType, List<ReleasedFinding>>();
        for (var type : WorkstreamType.values()) {
            var list = content.findings().stream()
                    .filter(f -> content.workstreams().get(f.workstreamId()) == type).toList();
            if (!list.isEmpty()) {
                byWorkstream.put(type, list);
            }
        }
        for (var entry : byWorkstream.entrySet()) {
            pageBreak(document);
            heading(document, text.workstreams.get(entry.getKey()));
            for (var f : entry.getValue()) {
                line(document, f.number() + " · " + f.title(), 13, true, ParagraphAlignment.LEFT);
                var rating = f.riskLevel() == null ? text.unrated
                        : text.levels.get(f.riskLevel()) + " (" + f.impact() + " × " + f.likelihood() + " = "
                                + f.riskScore() + ")";
                labelled(document, text.category, f.category());
                labelled(document, text.risk, rating);
                labelled(document, text.description, f.description());
                labelled(document, text.impact, f.impactDescription());
                labelled(document, text.monetary, money(f.monetaryImpact(), f.currency(), text.locale));
                labelled(document, text.recommendation, f.recommendation());
                if (!f.sources().isEmpty()) {
                    labelled(document, text.sources, String.join("; ", f.sources().stream()
                            .map(s -> s.pageReference() == null ? s.documentName()
                                    : s.documentName() + ", " + s.pageReference())
                            .toList()));
                }
                document.createParagraph();
            }
        }
    }

    static String money(BigDecimal amount, String currency, Locale locale) {
        if (amount == null) {
            return null;
        }
        var format = NumberFormat.getNumberInstance(locale);
        format.setMaximumFractionDigits(0);
        return (currency == null ? "" : currency + " ") + format.format(amount);
    }

    private static void heading(XWPFDocument document, String title) {
        var paragraph = document.createParagraph();
        paragraph.setSpacingBefore(240);
        paragraph.setSpacingAfter(120);
        var run = paragraph.createRun();
        run.setBold(true);
        run.setFontSize(16);
        run.setColor("1F3864");
        run.setText(title);
    }

    private static void line(XWPFDocument document, String value, int size, boolean bold, ParagraphAlignment align) {
        var paragraph = document.createParagraph();
        paragraph.setAlignment(align);
        var run = paragraph.createRun();
        run.setFontSize(size);
        run.setBold(bold);
        run.setText(value);
    }

    private static void labelled(XWPFDocument document, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        var paragraph = document.createParagraph();
        paragraph.setSpacingAfter(60);
        var head = paragraph.createRun();
        head.setBold(true);
        head.setText(label + ": ");
        var lines = value.split("\\R");
        var body = paragraph.createRun();
        for (var i = 0; i < lines.length; i++) {
            if (i > 0) {
                body.addBreak();
            }
            body.setText(lines[i], i);
        }
    }

    private static void cell(XWPFTableCell cell, String value, boolean bold, String fill) {
        var paragraph = cell.getParagraphs().getFirst();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        var run = paragraph.createRun();
        run.setBold(bold);
        run.setText(value);
        if (fill != null) {
            cell.setColor(fill);
            run.setColor("FFFFFF");
        }
    }

    private static void pageBreak(XWPFDocument document) {
        document.createParagraph().createRun().addBreak(BreakType.PAGE);
    }

    /** Fixed report wording in the two platform languages (PRD Bab 7). */
    private record Texts(Locale locale, String target, String version, String confidential, String summary,
            String summaryIntro, String level, String count, String keyRisks, String heatMap, String impactLikelihood,
            String category, String risk, String description, String impact, String monetary, String recommendation,
            String sources, String unrated, Map<RiskLevel, String> levels, Map<WorkstreamType, String> workstreams) {

        static Texts of(String language) {
            if ("en".equals(language)) {
                return new Texts(Locale.ENGLISH, "Target", "Version", "CONFIDENTIAL", "Executive summary",
                        "This report contains %d released findings.", "Risk level", "Findings", "Key risks",
                        "Risk heat map", "Impact \\ Likelihood", "Category", "Risk", "Description", "Impact",
                        "Monetary impact", "Recommendation", "Sources", "Not rated",
                        Map.of(RiskLevel.CRITICAL, "Critical", RiskLevel.HIGH, "High", RiskLevel.MEDIUM, "Medium",
                                RiskLevel.LOW, "Low"),
                        Map.of(WorkstreamType.FINANCIAL, "Financial", WorkstreamType.TAX, "Tax",
                                WorkstreamType.LEGAL, "Legal", WorkstreamType.COMMERCIAL, "Commercial",
                                WorkstreamType.OPERATIONAL, "Operational", WorkstreamType.HR, "Human resources",
                                WorkstreamType.IT_CYBER, "IT & cyber security",
                                WorkstreamType.ESG_COMPLIANCE, "ESG & compliance"));
            }
            return new Texts(Locale.forLanguageTag("id-ID"), "Target", "Versi", "RAHASIA", "Ringkasan eksekutif",
                    "Laporan ini memuat %d temuan yang telah dirilis.", "Tingkat risiko", "Temuan", "Risiko utama",
                    "Peta risiko", "Dampak \\ Kemungkinan", "Kategori", "Risiko", "Deskripsi", "Dampak",
                    "Dampak finansial", "Rekomendasi", "Sumber", "Belum dinilai",
                    Map.of(RiskLevel.CRITICAL, "Kritis", RiskLevel.HIGH, "Tinggi", RiskLevel.MEDIUM, "Sedang",
                            RiskLevel.LOW, "Rendah"),
                    Map.of(WorkstreamType.FINANCIAL, "Keuangan", WorkstreamType.TAX, "Perpajakan",
                            WorkstreamType.LEGAL, "Hukum", WorkstreamType.COMMERCIAL, "Komersial",
                            WorkstreamType.OPERATIONAL, "Operasional", WorkstreamType.HR, "Sumber daya manusia",
                            WorkstreamType.IT_CYBER, "TI & keamanan siber",
                            WorkstreamType.ESG_COMPLIANCE, "ESG & kepatuhan"));
        }
    }
}
