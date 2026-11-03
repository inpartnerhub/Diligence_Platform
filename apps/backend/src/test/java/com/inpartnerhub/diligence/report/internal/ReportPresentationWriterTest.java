package com.inpartnerhub.diligence.report.internal;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;

import com.inpartnerhub.diligence.finding.FindingDirectory.ReleasedFinding;
import com.inpartnerhub.diligence.finding.FindingDirectory.Source;
import com.inpartnerhub.diligence.finding.RiskLevel;
import com.inpartnerhub.diligence.finding.RiskMatrix;
import com.inpartnerhub.diligence.project.WorkstreamType;

import static org.assertj.core.api.Assertions.assertThat;

class ReportPresentationWriterTest {

    private static final UUID LEGAL = UUID.randomUUID();
    private static final UUID TAX = UUID.randomUUID();

    @Test
    void generatesValidPowerPointPresentationInEnglish() throws Exception {
        var finding1 = new ReleasedFinding(
                UUID.randomUUID(), "LEG-001", LEGAL, "Change of Control Clause",
                "Corporate", "Material consent required upon acquisition.",
                "Customer termination risk.", new BigDecimal("5000000000"), "IDR",
                "Obtain waiver prior to signing.", 5, 4, 20, RiskLevel.CRITICAL,
                List.of(new Source("Agreement.pdf", "Section 12.2")));

        var finding2 = new ReleasedFinding(
                UUID.randomUUID(), "TAX-001", TAX, "Transfer Pricing Documentation",
                "Corporate Tax", "Local file missing for FY 2024.",
                "Potential audit penalties.", null, null,
                "Prepare benchmarking study.", 3, 3, 9, RiskLevel.MEDIUM,
                List.of());

        var content = new ReportPresentationWriter.Content(
                "DD-01", "Project Phoenix", "Target Corp Ltd",
                "Due Diligence Slide Deck", "v1.0", 1, "en",
                Instant.now(), ZoneId.of("Asia/Jakarta"),
                Map.of(LEGAL, WorkstreamType.LEGAL, TAX, WorkstreamType.TAX),
                List.of(finding1, finding2), RiskMatrix.DEFAULT,
                "partner@example.com");

        byte[] pptxBytes = ReportPresentationWriter.write(content);
        assertThat(pptxBytes).isNotEmpty();

        try (var ppt = new XMLSlideShow(new ByteArrayInputStream(pptxBytes))) {
            // Cover, Summary, HeatMap, Workstream breakdown, and 2 findings = 6 slides
            assertThat(ppt.getSlides()).hasSize(6);
            assertThat(ppt.getPageSize().getWidth()).isEqualTo(960.0);
            assertThat(ppt.getPageSize().getHeight()).isEqualTo(540.0);
        }
    }

    @Test
    void generatesValidPowerPointPresentationInIndonesian() throws Exception {
        var finding = new ReleasedFinding(
                UUID.randomUUID(), "LEG-002", LEGAL, "Izin Usaha Kadaluarsa",
                "Perizinan", "SLF bangunan belum diperpanjang.",
                "Sanksi administratif.", null, null,
                "Segera perpanjang ke dinas terkait.", 4, 3, 12, RiskLevel.HIGH,
                List.of(new Source("Perizinan.pdf", "Halaman 4")));

        var content = new ReportPresentationWriter.Content(
                "DD-02", "Proyek Merdeka", "PT Target Nusantara",
                "Laporan Uji Tuntas Ringkas", "v2.1", 2, "id",
                Instant.now(), ZoneId.of("Asia/Jakarta"),
                Map.of(LEGAL, WorkstreamType.LEGAL),
                List.of(finding), RiskMatrix.DEFAULT,
                "Budi (budi@inpartnerhub.com)");

        byte[] pptxBytes = ReportPresentationWriter.write(content);
        assertThat(pptxBytes).isNotEmpty();

        try (var ppt = new XMLSlideShow(new ByteArrayInputStream(pptxBytes))) {
            assertThat(ppt.getSlides()).hasSize(5); // Cover, Summary, HeatMap, Workstream, 1 finding
        }
    }
}
