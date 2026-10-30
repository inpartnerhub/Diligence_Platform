package com.inpartnerhub.diligence.finding.internal;

import java.util.Collection;
import java.util.Map;

import com.inpartnerhub.diligence.project.WorkstreamType;

/** Finding numbers per workstream ("F-LEG-001"), stable references for reports and Q&A. */
final class FindingNumbers {

    private static final Map<WorkstreamType, String> PREFIXES = Map.of(
            WorkstreamType.FINANCIAL, "FIN",
            WorkstreamType.TAX, "TAX",
            WorkstreamType.LEGAL, "LEG",
            WorkstreamType.COMMERCIAL, "COM",
            WorkstreamType.OPERATIONAL, "OPS",
            WorkstreamType.HR, "HR",
            WorkstreamType.IT_CYBER, "ITC",
            WorkstreamType.ESG_COMPLIANCE, "ESG");

    private FindingNumbers() {
    }

    static String prefix(WorkstreamType workstream) {
        return "F-" + PREFIXES.get(workstream) + "-";
    }

    static String next(WorkstreamType workstream, Collection<String> existing) {
        var prefix = prefix(workstream);
        var max = existing.stream()
                .filter(n -> n.startsWith(prefix))
                .map(n -> n.substring(prefix.length()))
                .filter(n -> !n.isEmpty() && n.length() < 9 && n.chars().allMatch(Character::isDigit))
                .mapToInt(Integer::parseInt)
                .max().orElse(0);
        return prefix + "%03d".formatted(max + 1);
    }
}
