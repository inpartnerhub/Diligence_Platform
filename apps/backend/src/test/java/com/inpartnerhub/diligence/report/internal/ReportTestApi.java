package com.inpartnerhub.diligence.report.internal;

import java.util.UUID;

/** Test helper exposing the report service to integration tests of other packages. */
public class ReportTestApi {

    private final ReportService reports;

    ReportTestApi(ReportService reports) {
        this.reports = reports;
    }

    public UUID generate(UUID projectId, String title) {
        return reports.generate(projectId, title, "id").getId();
    }

    public void issue(UUID projectId, UUID reportId) {
        reports.issue(projectId, reportId);
    }

    /** Report versions the signed-in user can see. */
    public int versions(UUID projectId) {
        return reports.list(projectId).size();
    }
}
