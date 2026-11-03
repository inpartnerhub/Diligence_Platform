package com.inpartnerhub.diligence.report.internal;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;

import com.inpartnerhub.diligence.finding.FindingDirectory.ReleasedFinding;
import com.inpartnerhub.diligence.finding.RiskLevel;
import com.inpartnerhub.diligence.finding.RiskMatrix;
import com.inpartnerhub.diligence.project.WorkstreamType;

/**
 * Generates due diligence presentations as styled PowerPoint (.pptx) decks (PRD RPT-04):
 * - Executive cover slide with metadata and confidentiality watermark
 * - Executive summary & risk count metrics
 * - 5x5 visual risk heat map
 * - Workstream scope breakdown
 * - Detailed single-slide summaries for findings (High and Critical red flags prioritised)
 */
final class ReportPresentationWriter {

    static final int SLIDE_WIDTH = 960;
    static final int SLIDE_HEIGHT = 540;

    static final Color COLOR_PRIMARY = new Color(15, 23, 42);     // #0F172A
    static final Color COLOR_SECONDARY = new Color(71, 85, 105);  // #475569
    static final Color COLOR_MUTED = new Color(148, 163, 184);    // #94A3B8
    static final Color COLOR_BG_LIGHT = new Color(248, 250, 252); // #F8FAFC
    static final Color COLOR_BORDER = new Color(226, 232, 240);   // #E2E8F0
    static final Color COLOR_WHITE = Color.WHITE;

    static final Map<RiskLevel, Color> RISK_COLORS = Map.of(
            RiskLevel.CRITICAL, new Color(207, 19, 34),
            RiskLevel.HIGH, new Color(212, 107, 8),
            RiskLevel.MEDIUM, new Color(212, 177, 6),
            RiskLevel.LOW, new Color(56, 158, 13));

    private static final String SCORE_GTE_PREFIX = " (Score ≥ ";

    record Content(
            String projectCode,
            String projectName,
            String targetName,
            String title,
            String versionLabel,
            int versionNumber,
            String language,
            Instant generatedAt,
            ZoneId zone,
            Map<UUID, WorkstreamType> workstreams,
            List<ReleasedFinding> findings,
            RiskMatrix matrix,
            String watermarkUser) {
    }

    private ReportPresentationWriter() {
    }

    static byte[] write(Content content) {
        var text = ReportTexts.of(content.language());
        try (var ppt = new XMLSlideShow(); var out = new ByteArrayOutputStream()) {
            ppt.setPageSize(new Dimension(SLIDE_WIDTH, SLIDE_HEIGHT));

            coverSlide(ppt, content, text);
            executiveSummarySlide(ppt, content, text);
            heatMapSlide(ppt, content, text);
            workstreamBreakdownSlide(ppt, content, text);
            findingSlides(ppt, content, text);

            ppt.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("The PowerPoint presentation could not be generated", ex);
        }
    }

    private static void coverSlide(XMLSlideShow ppt, Content content, ReportTexts text) {
        var slide = ppt.createSlide();

        // Top decorative bar
        var bar = slide.createTable();
        bar.setAnchor(new Rectangle2D.Double(0, 0, SLIDE_WIDTH, 8));
        var barRow = bar.addRow();
        var barCell = barRow.addCell();
        barCell.setFillColor(COLOR_PRIMARY);

        // Title box
        var tb = slide.createTextBox();
        tb.setAnchor(new Rectangle2D.Double(60, 100, 840, 240));

        var pTag = tb.addNewTextParagraph();
        var rTag = pTag.addNewTextRun();
        rTag.setText(content.projectCode() + " · " + content.projectName().toUpperCase());
        rTag.setFontSize(14.0);
        rTag.setBold(true);
        rTag.setFontColor(COLOR_SECONDARY);

        var pTitle = tb.addNewTextParagraph();
        pTitle.setSpaceBefore(12.0);
        var rTitle = pTitle.addNewTextRun();
        rTitle.setText(content.title());
        rTitle.setFontSize(32.0);
        rTitle.setBold(true);
        rTitle.setFontColor(COLOR_PRIMARY);

        if (content.targetName() != null && !content.targetName().isBlank()) {
            var pTarget = tb.addNewTextParagraph();
            pTarget.setSpaceBefore(8.0);
            var rTarget = pTarget.addNewTextRun();
            rTarget.setText(text.target() + ": " + content.targetName());
            rTarget.setFontSize(16.0);
            rTarget.setFontColor(COLOR_SECONDARY);
        }

        var pMeta = tb.addNewTextParagraph();
        pMeta.setSpaceBefore(16.0);
        var rMeta = pMeta.addNewTextRun();
        var dateStr = DateTimeFormatter.ofPattern("d MMMM yyyy", text.locale())
                .format(content.generatedAt().atZone(content.zone()));
        rMeta.setText(text.version() + " " + content.versionLabel() + " · " + dateStr);
        rMeta.setFontSize(13.0);
        rMeta.setFontColor(COLOR_MUTED);

        // Confidentiality watermark footer
        addSlideFooter(slide, content, text, true);
    }

    private static void executiveSummarySlide(XMLSlideShow ppt, Content content, ReportTexts text) {
        var slide = ppt.createSlide();
        addSlideHeader(slide, text.summary(), content.projectCode() + " · Executive Summary");

        var counts = countByLevel(content.findings());

        // Left side: summary metrics table
        var table = slide.createTable();
        table.setAnchor(new Rectangle2D.Double(60, 110, 320, 240));

        var headerRow = table.addRow();
        addCell(headerRow, text.level(), true, COLOR_PRIMARY, COLOR_WHITE, 12.0, TextAlign.LEFT);
        addCell(headerRow, text.count(), true, COLOR_PRIMARY, COLOR_WHITE, 12.0, TextAlign.RIGHT);

        for (var level : List.of(RiskLevel.CRITICAL, RiskLevel.HIGH, RiskLevel.MEDIUM, RiskLevel.LOW)) {
            var row = table.addRow();
            addCell(row, text.levels().get(level), true, RISK_COLORS.get(level), COLOR_WHITE, 11.0, TextAlign.LEFT);
            addCell(row, String.valueOf(counts.get(level)), false, COLOR_BG_LIGHT, COLOR_PRIMARY, 11.0, TextAlign.RIGHT);
        }

        var totalRow = table.addRow();
        addCell(totalRow, text.total(), true, COLOR_BORDER, COLOR_PRIMARY, 12.0, TextAlign.LEFT);
        addCell(totalRow, String.valueOf(content.findings().size()), true, COLOR_BORDER, COLOR_PRIMARY, 12.0, TextAlign.RIGHT);

        // Right side: Key Risks list
        var rightTb = slide.createTextBox();
        rightTb.setAnchor(new Rectangle2D.Double(420, 110, 480, 360));

        var pKey = rightTb.addNewTextParagraph();
        var rKey = pKey.addNewTextRun();
        rKey.setText(text.keyRisks());
        rKey.setFontSize(14.0);
        rKey.setBold(true);
        rKey.setFontColor(COLOR_PRIMARY);

        var criticalFindings = content.findings().stream()
                .filter(f -> f.riskLevel() == RiskLevel.CRITICAL || f.riskLevel() == RiskLevel.HIGH)
                .limit(7)
                .toList();

        if (criticalFindings.isEmpty()) {
            var pEmpty = rightTb.addNewTextParagraph();
            pEmpty.setSpaceBefore(12.0);
            var rEmpty = pEmpty.addNewTextRun();
            rEmpty.setText("No Critical or High risk findings identified.");
            rEmpty.setFontSize(12.0);
            rEmpty.setFontColor(COLOR_MUTED);
        } else {
            for (var f : criticalFindings) {
                var pItem = rightTb.addNewTextParagraph();
                pItem.setSpaceBefore(8.0);
                var rBullet = pItem.addNewTextRun();
                rBullet.setText("• [" + f.number() + "] ");
                rBullet.setBold(true);
                rBullet.setFontSize(11.0);
                rBullet.setFontColor(RISK_COLORS.get(f.riskLevel()));

                var rTitle = pItem.addNewTextRun();
                rTitle.setText(f.title() + " (" + text.levels().get(f.riskLevel()) + ")");
                rTitle.setFontSize(11.0);
                rTitle.setFontColor(COLOR_PRIMARY);
            }
        }

        addSlideFooter(slide, content, text, false);
    }

    private static void heatMapSlide(XMLSlideShow ppt, Content content, ReportTexts text) {
        var slide = ppt.createSlide();
        addSlideHeader(slide, text.heatMap(), content.projectCode() + " · 5x5 Risk Matrix");

        var cells = new HashMap<String, Integer>();
        for (var f : content.findings()) {
            if (f.impact() != null && f.likelihood() != null) {
                cells.merge(f.impact() + ":" + f.likelihood(), 1, Integer::sum);
            }
        }

        // 6 columns x 6 rows heat map table
        var table = slide.createTable();
        table.setAnchor(new Rectangle2D.Double(120, 110, 480, 280));

        // Header row: Likelihood 1..5
        var headerRow = table.addRow();
        addCell(headerRow, "I \\ L", true, COLOR_PRIMARY, COLOR_WHITE, 11.0, TextAlign.CENTER);
        for (var l = 1; l <= 5; l++) {
            addCell(headerRow, "L" + l, true, COLOR_PRIMARY, COLOR_WHITE, 11.0, TextAlign.CENTER);
        }

        // Rows for Impact 5 down to 1
        for (var i = 5; i >= 1; i--) {
            var row = table.addRow();
            addCell(row, "I" + i, true, COLOR_PRIMARY, COLOR_WHITE, 11.0, TextAlign.CENTER);
            for (var l = 1; l <= 5; l++) {
                var level = content.matrix().rate(i, l).level();
                var count = cells.get(i + ":" + l);
                var textInCell = count == null ? "" : String.valueOf(count);
                var color = RISK_COLORS.get(level);
                addCell(row, textInCell, true, color, COLOR_WHITE, 12.0, TextAlign.CENTER);
            }
        }

        // Right side: legend and explanation
        var legendTb = slide.createTextBox();
        legendTb.setAnchor(new Rectangle2D.Double(630, 110, 270, 280));

        var pLeg = legendTb.addNewTextParagraph();
        var rLeg = pLeg.addNewTextRun();
        rLeg.setText("Risk Thresholds");
        rLeg.setBold(true);
        rLeg.setFontSize(14.0);
        rLeg.setFontColor(COLOR_PRIMARY);

        addLegendItem(legendTb, text.levels().get(RiskLevel.CRITICAL) + SCORE_GTE_PREFIX + content.matrix().criticalFrom() + ")",
                RISK_COLORS.get(RiskLevel.CRITICAL));
        addLegendItem(legendTb, text.levels().get(RiskLevel.HIGH) + SCORE_GTE_PREFIX + content.matrix().highFrom() + ")",
                RISK_COLORS.get(RiskLevel.HIGH));
        addLegendItem(legendTb, text.levels().get(RiskLevel.MEDIUM) + SCORE_GTE_PREFIX + content.matrix().mediumFrom() + ")",
                RISK_COLORS.get(RiskLevel.MEDIUM));
        addLegendItem(legendTb, text.levels().get(RiskLevel.LOW) + " (Score < " + content.matrix().mediumFrom() + ")",
                RISK_COLORS.get(RiskLevel.LOW));

        var pNote = legendTb.addNewTextParagraph();
        pNote.setSpaceBefore(16.0);
        var rNote = pNote.addNewTextRun();
        rNote.setText("Score = Impact (1-5) × Likelihood (1-5)");
        rNote.setFontSize(10.0);
        rNote.setFontColor(COLOR_MUTED);

        addSlideFooter(slide, content, text, false);
    }

    private static void workstreamBreakdownSlide(XMLSlideShow ppt, Content content, ReportTexts text) {
        var slide = ppt.createSlide();
        addSlideHeader(slide, text.workstreamBreakdown(), content.projectCode() + " · Scope & Severity");

        var byWorkstream = new LinkedHashMap<UUID, List<ReleasedFinding>>();
        for (var f : content.findings()) {
            byWorkstream.computeIfAbsent(f.workstreamId(), k -> new java.util.ArrayList<>()).add(f);
        }

        var table = slide.createTable();
        table.setAnchor(new Rectangle2D.Double(60, 110, 840, 40.0 + (byWorkstream.size() * 30.0)));

        var headerRow = table.addRow();
        addCell(headerRow, "Workstream", true, COLOR_PRIMARY, COLOR_WHITE, 11.0, TextAlign.LEFT);
        addCell(headerRow, text.levels().get(RiskLevel.CRITICAL), true, RISK_COLORS.get(RiskLevel.CRITICAL), COLOR_WHITE, 11.0, TextAlign.CENTER);
        addCell(headerRow, text.levels().get(RiskLevel.HIGH), true, RISK_COLORS.get(RiskLevel.HIGH), COLOR_WHITE, 11.0, TextAlign.CENTER);
        addCell(headerRow, text.levels().get(RiskLevel.MEDIUM), true, RISK_COLORS.get(RiskLevel.MEDIUM), COLOR_WHITE, 11.0, TextAlign.CENTER);
        addCell(headerRow, text.levels().get(RiskLevel.LOW), true, RISK_COLORS.get(RiskLevel.LOW), COLOR_WHITE, 11.0, TextAlign.CENTER);
        addCell(headerRow, text.total(), true, COLOR_PRIMARY, COLOR_WHITE, 11.0, TextAlign.CENTER);

        for (var entry : byWorkstream.entrySet()) {
            var row = table.addRow();
            var type = content.workstreams().get(entry.getKey());
            var name = type == null ? text.category() : text.workstreams().getOrDefault(type, type.name());

            var fList = entry.getValue();
            var cCrit = fList.stream().filter(f -> f.riskLevel() == RiskLevel.CRITICAL).count();
            var cHigh = fList.stream().filter(f -> f.riskLevel() == RiskLevel.HIGH).count();
            var cMed = fList.stream().filter(f -> f.riskLevel() == RiskLevel.MEDIUM).count();
            var cLow = fList.stream().filter(f -> f.riskLevel() == RiskLevel.LOW).count();

            addCell(row, name, true, COLOR_BG_LIGHT, COLOR_PRIMARY, 11.0, TextAlign.LEFT);
            addCell(row, String.valueOf(cCrit), false, COLOR_BG_LIGHT, COLOR_PRIMARY, 11.0, TextAlign.CENTER);
            addCell(row, String.valueOf(cHigh), false, COLOR_BG_LIGHT, COLOR_PRIMARY, 11.0, TextAlign.CENTER);
            addCell(row, String.valueOf(cMed), false, COLOR_BG_LIGHT, COLOR_PRIMARY, 11.0, TextAlign.CENTER);
            addCell(row, String.valueOf(cLow), false, COLOR_BG_LIGHT, COLOR_PRIMARY, 11.0, TextAlign.CENTER);
            addCell(row, String.valueOf(fList.size()), true, COLOR_BORDER, COLOR_PRIMARY, 11.0, TextAlign.CENTER);
        }

        addSlideFooter(slide, content, text, false);
    }

    private static void findingSlides(XMLSlideShow ppt, Content content, ReportTexts text) {
        var sorted = content.findings().stream()
                .sorted((a, b) -> Integer.compare(score(b), score(a)))
                .toList();

        for (var f : sorted) {
            buildFindingSlide(ppt, f, content, text);
        }
    }

    private static void buildFindingSlide(XMLSlideShow ppt, ReleasedFinding f, Content content, ReportTexts text) {
        var slide = ppt.createSlide();
        buildFindingHeader(slide, f, content, text);
        buildFindingLeftBox(slide, f, text);
        buildFindingRightBox(slide, f, text);
        addSlideFooter(slide, content, text, false);
    }

    private static void buildFindingHeader(XSLFSlide slide, ReleasedFinding f, Content content, ReportTexts text) {
        var type = content.workstreams().get(f.workstreamId());
        var wsName = type == null ? f.category() : text.workstreams().getOrDefault(type, type.name());
        var level = f.riskLevel();
        var levelName = level == null ? text.unrated() : text.levels().get(level);

        var headerTb = slide.createTextBox();
        headerTb.setAnchor(new Rectangle2D.Double(60, 20, 840, 70));

        var pSub = headerTb.addNewTextParagraph();
        var rSub = pSub.addNewTextRun();
        rSub.setText(content.projectCode() + " · " + wsName + " · " + f.category());
        rSub.setFontSize(11.0);
        rSub.setFontColor(COLOR_SECONDARY);

        var pTitle = headerTb.addNewTextParagraph();
        pTitle.setSpaceBefore(4.0);
        var rNum = pTitle.addNewTextRun();
        rNum.setText("[" + f.number() + "] ");
        rNum.setBold(true);
        rNum.setFontSize(18.0);
        rNum.setFontColor(level != null ? RISK_COLORS.get(level) : COLOR_PRIMARY);

        var rTitle = pTitle.addNewTextRun();
        rTitle.setText(f.title());
        rTitle.setBold(true);
        rTitle.setFontSize(18.0);
        rTitle.setFontColor(COLOR_PRIMARY);

        var rBadge = pTitle.addNewTextRun();
        rBadge.setText("   [" + levelName.toUpperCase() + (f.riskScore() != null ? " · " + f.riskScore() : "") + "]");
        rBadge.setFontSize(12.0);
        rBadge.setBold(true);
        rBadge.setFontColor(level != null ? RISK_COLORS.get(level) : COLOR_SECONDARY);
    }

    private static void buildFindingLeftBox(XSLFSlide slide, ReleasedFinding f, ReportTexts text) {
        var leftTb = slide.createTextBox();
        leftTb.setAnchor(new Rectangle2D.Double(60, 95, 400, 360));

        var pDescHead = leftTb.addNewTextParagraph();
        var rDescHead = pDescHead.addNewTextRun();
        rDescHead.setText(text.description());
        rDescHead.setBold(true);
        rDescHead.setFontSize(12.0);
        rDescHead.setFontColor(COLOR_PRIMARY);

        var pDesc = leftTb.addNewTextParagraph();
        pDesc.setSpaceBefore(4.0);
        var rDesc = pDesc.addNewTextRun();
        rDesc.setText(f.description());
        rDesc.setFontSize(10.5);
        rDesc.setFontColor(COLOR_SECONDARY);

        if (f.impactDescription() != null && !f.impactDescription().isBlank()) {
            var pImpHead = leftTb.addNewTextParagraph();
            pImpHead.setSpaceBefore(12.0);
            var rImpHead = pImpHead.addNewTextRun();
            rImpHead.setText(text.impact());
            rImpHead.setBold(true);
            rImpHead.setFontSize(12.0);
            rImpHead.setFontColor(COLOR_PRIMARY);

            var pImp = leftTb.addNewTextParagraph();
            pImp.setSpaceBefore(4.0);
            var rImp = pImp.addNewTextRun();
            rImp.setText(f.impactDescription());
            rImp.setFontSize(10.5);
            rImp.setFontColor(COLOR_SECONDARY);
        }

        if (f.monetaryImpact() != null) {
            var pMon = leftTb.addNewTextParagraph();
            pMon.setSpaceBefore(8.0);
            var rMon = pMon.addNewTextRun();
            rMon.setText(text.monetary() + ": " + formatMonetary(f.monetaryImpact(), f.currency()));
            rMon.setBold(true);
            rMon.setFontSize(11.0);
            rMon.setFontColor(COLOR_PRIMARY);
        }
    }

    private static void buildFindingRightBox(XSLFSlide slide, ReleasedFinding f, ReportTexts text) {
        var rightTb = slide.createTextBox();
        rightTb.setAnchor(new Rectangle2D.Double(480, 95, 420, 360));

        if (f.recommendation() != null && !f.recommendation().isBlank()) {
            var pRecHead = rightTb.addNewTextParagraph();
            var rRecHead = pRecHead.addNewTextRun();
            rRecHead.setText(text.recommendation());
            rRecHead.setBold(true);
            rRecHead.setFontSize(12.0);
            rRecHead.setFontColor(COLOR_PRIMARY);

            var pRec = rightTb.addNewTextParagraph();
            pRec.setSpaceBefore(4.0);
            var rRec = pRec.addNewTextRun();
            rRec.setText(f.recommendation());
            rRec.setFontSize(10.5);
            rRec.setFontColor(COLOR_SECONDARY);
        }

        if (!f.sources().isEmpty()) {
            var pSrcHead = rightTb.addNewTextParagraph();
            pSrcHead.setSpaceBefore(12.0);
            var rSrcHead = pSrcHead.addNewTextRun();
            rSrcHead.setText(text.sources());
            rSrcHead.setBold(true);
            rSrcHead.setFontSize(12.0);
            rSrcHead.setFontColor(COLOR_PRIMARY);

            for (var s : f.sources()) {
                var pSrc = rightTb.addNewTextParagraph();
                pSrc.setSpaceBefore(4.0);
                var rSrc = pSrc.addNewTextRun();
                var ref = s.pageReference() == null ? "" : " (" + s.pageReference() + ")";
                rSrc.setText("• " + s.documentName() + ref);
                rSrc.setFontSize(10.0);
                rSrc.setFontColor(COLOR_SECONDARY);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void addSlideHeader(XSLFSlide slide, String title, String subtitle) {
        var tb = slide.createTextBox();
        tb.setAnchor(new Rectangle2D.Double(60, 30, 840, 60));

        var pSub = tb.addNewTextParagraph();
        var rSub = pSub.addNewTextRun();
        rSub.setText(subtitle.toUpperCase());
        rSub.setFontSize(10.0);
        rSub.setBold(true);
        rSub.setFontColor(COLOR_MUTED);

        var pTitle = tb.addNewTextParagraph();
        pTitle.setSpaceBefore(4.0);
        var rTitle = pTitle.addNewTextRun();
        rTitle.setText(title);
        rTitle.setFontSize(22.0);
        rTitle.setBold(true);
        rTitle.setFontColor(COLOR_PRIMARY);
    }

    private static void addSlideFooter(XSLFSlide slide, Content content, ReportTexts text, boolean isCover) {
        var tb = slide.createTextBox();
        tb.setAnchor(new Rectangle2D.Double(60, 490, 840, 30));

        var p = tb.addNewTextParagraph();
        p.setTextAlign(isCover ? TextAlign.CENTER : TextAlign.LEFT);

        var r = p.addNewTextRun();
        var watermark = content.watermarkUser() != null && !content.watermarkUser().isBlank()
                ? " · " + text.preparedFor().formatted(content.watermarkUser())
                : "";
        r.setText(text.strictlyConfidential() + watermark);
        r.setFontSize(9.0);
        r.setBold(true);
        r.setFontColor(COLOR_MUTED);
    }

    private static void addLegendItem(XSLFTextBox tb, String label, Color color) {
        var p = tb.addNewTextParagraph();
        p.setSpaceBefore(8.0);
        var rSquare = p.addNewTextRun();
        rSquare.setText("■ ");
        rSquare.setFontSize(12.0);
        rSquare.setFontColor(color);

        var rText = p.addNewTextRun();
        rText.setText(label);
        rText.setFontSize(11.0);
        rText.setFontColor(COLOR_PRIMARY);
    }

    private static void addCell(XSLFTableRow row, String text, boolean bold, Color bg, Color fg, double size, TextAlign align) {
        var cell = row.addCell();
        cell.setText(text);
        cell.setFillColor(bg);
        var p = cell.getTextParagraphs().get(0);
        p.setTextAlign(align);
        if (!p.getTextRuns().isEmpty()) {
            var r = p.getTextRuns().get(0);
            r.setBold(bold);
            r.setFontColor(fg);
            r.setFontSize(size);
        }
    }

    private static Map<RiskLevel, Integer> countByLevel(List<ReleasedFinding> findings) {
        var counts = new EnumMap<RiskLevel, Integer>(RiskLevel.class);
        for (var level : RiskLevel.values()) {
            counts.put(level, 0);
        }
        for (var f : findings) {
            if (f.riskLevel() != null) {
                counts.merge(f.riskLevel(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static int score(ReleasedFinding f) {
        return f.riskScore() != null ? f.riskScore() : 0;
    }

    private static String formatMonetary(BigDecimal amount, String currency) {
        var cur = currency == null || currency.isBlank() ? "IDR" : currency.toUpperCase();
        return NumberFormat.getNumberInstance(Locale.US).format(amount) + " " + cur;
    }
}
