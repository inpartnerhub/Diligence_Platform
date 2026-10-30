package com.inpartnerhub.diligence.request.internal;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.inpartnerhub.diligence.project.WorkstreamType;

/** Item numbers per workstream ("LEG-001") and the workstream codes accepted in Excel imports. */
final class RequestNumbers {

    static final Map<WorkstreamType, String> PREFIXES = Map.of(
            WorkstreamType.FINANCIAL, "FIN",
            WorkstreamType.TAX, "TAX",
            WorkstreamType.LEGAL, "LEG",
            WorkstreamType.COMMERCIAL, "COM",
            WorkstreamType.OPERATIONAL, "OPS",
            WorkstreamType.HR, "HR",
            WorkstreamType.IT_CYBER, "ITC",
            WorkstreamType.ESG_COMPLIANCE, "ESG");

    private static final Pattern NUMBER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");

    private RequestNumbers() {
    }

    /** The next free number for the workstream, given the numbers already used with its prefix. */
    static String next(WorkstreamType workstream, Collection<String> existing) {
        var prefix = PREFIXES.get(workstream) + "-";
        var max = existing.stream()
                .filter(n -> n.startsWith(prefix))
                .map(n -> n.substring(prefix.length()))
                .filter(n -> n.chars().allMatch(Character::isDigit) && !n.isEmpty() && n.length() < 9)
                .mapToInt(Integer::parseInt)
                .max().orElse(0);
        return prefix + "%03d".formatted(max + 1);
    }

    static String prefix(WorkstreamType workstream) {
        return PREFIXES.get(workstream) + "-";
    }

    static boolean isValid(String number) {
        return number != null && NUMBER.matcher(number).matches();
    }

    /** Accepts the enum name ("LEGAL", "IT_CYBER", "it cyber") or the number prefix ("LEG"). */
    static Optional<WorkstreamType> parseWorkstream(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        var value = raw.strip().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (var type : WorkstreamType.values()) {
            if (type.name().equals(value) || PREFIXES.get(type).equals(value)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
