package com.inpartnerhub.diligence.report.internal;

import java.util.Locale;
import java.util.Map;

import com.inpartnerhub.diligence.finding.RiskLevel;
import com.inpartnerhub.diligence.project.WorkstreamType;

/** Fixed report wording in the two platform languages (PRD Bab 7). */
record ReportTexts(
        Locale locale,
        String target,
        String version,
        String confidential,
        String strictlyConfidential,
        String preparedFor,
        String summary,
        String summaryIntro,
        String level,
        String count,
        String keyRisks,
        String heatMap,
        String impactLikelihood,
        String category,
        String risk,
        String description,
        String impact,
        String monetary,
        String recommendation,
        String sources,
        String unrated,
        String workstreamBreakdown,
        String total,
        Map<RiskLevel, String> levels,
        Map<WorkstreamType, String> workstreams) {

    static ReportTexts of(String language) {
        if ("en".equals(language)) {
            return new ReportTexts(
                    Locale.ENGLISH,
                    "Target",
                    "Version",
                    "CONFIDENTIAL",
                    "STRICTLY CONFIDENTIAL",
                    "Prepared for %s",
                    "Executive summary",
                    "This report contains %d released findings.",
                    "Risk level",
                    "Findings",
                    "Key risks",
                    "Risk heat map",
                    "Impact \\ Likelihood",
                    "Category",
                    "Risk",
                    "Description",
                    "Impact",
                    "Monetary impact",
                    "Recommendation",
                    "Sources",
                    "Not rated",
                    "Workstream breakdown",
                    "Total",
                    Map.of(
                            RiskLevel.CRITICAL, "Critical",
                            RiskLevel.HIGH, "High",
                            RiskLevel.MEDIUM, "Medium",
                            RiskLevel.LOW, "Low"),
                    Map.of(
                            WorkstreamType.FINANCIAL, "Financial",
                            WorkstreamType.TAX, "Tax",
                            WorkstreamType.LEGAL, "Legal",
                            WorkstreamType.COMMERCIAL, "Commercial",
                            WorkstreamType.OPERATIONAL, "Operational",
                            WorkstreamType.HR, "Human resources",
                            WorkstreamType.IT_CYBER, "IT & cyber security",
                            WorkstreamType.ESG_COMPLIANCE, "ESG & compliance"));
        }
        return new ReportTexts(
                Locale.forLanguageTag("id-ID"),
                "Target",
                "Versi",
                "RAHASIA",
                "SANGAT RAHASIA",
                "Disiapkan untuk %s",
                "Ringkasan eksekutif",
                "Laporan ini memuat %d temuan yang telah dirilis.",
                "Tingkat risiko",
                "Temuan",
                "Risiko utama",
                "Peta risiko",
                "Dampak \\ Kemungkinan",
                "Kategori",
                "Risiko",
                "Deskripsi",
                "Dampak",
                "Dampak finansial",
                "Rekomendasi",
                "Sumber",
                "Belum dinilai",
                "Rincian bidang kerja",
                "Total",
                Map.of(
                        RiskLevel.CRITICAL, "Kritis",
                        RiskLevel.HIGH, "Tinggi",
                        RiskLevel.MEDIUM, "Sedang",
                        RiskLevel.LOW, "Rendah"),
                Map.of(
                        WorkstreamType.FINANCIAL, "Keuangan",
                        WorkstreamType.TAX, "Perpajakan",
                        WorkstreamType.LEGAL, "Hukum",
                        WorkstreamType.COMMERCIAL, "Komersial",
                        WorkstreamType.OPERATIONAL, "Operasional",
                        WorkstreamType.HR, "Sumber daya manusia",
                        WorkstreamType.IT_CYBER, "TI & keamanan siber",
                        WorkstreamType.ESG_COMPLIANCE, "ESG & kepatuhan"));
    }
}
