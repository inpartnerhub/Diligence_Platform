package com.inpartnerhub.diligence.dataroom.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * AI-assisted extractor for standard key contractual clauses in legal and financial diligence documents (PRD DOC-09).
 */
@Component
class KeyClauseExtractor {

    record ExtractedClause(String clauseType, double confidence, int pageNumber, String extractedText) {
    }

    private static final List<ClauseRule> RULES = List.of(
            new ClauseRule("CHANGE_OF_CONTROL", Pattern.compile(
                    "(?i)\\b(change\\s+in\\s+control|change\\s+of\\s+control|sale\\s+of\\s+substantially\\s+all\\s+(assets|shares)|merger\\s+or\\s+consolidation|acquisition\\s+of\\s+control)\\b"),
                    0.95),
            new ClauseRule("NON_COMPETE", Pattern.compile(
                    "(?i)\\b(non-?compete|non-?competition|covenant\\s+not\\s+to\\s+compete|restrictive\\s+covenant|non-?solicitation|covenant\\s+not\\s+to\\s+solicit)\\b"),
                    0.94),
            new ClauseRule("ASSIGNMENT", Pattern.compile(
                    "(?i)\\b(assignment|assignability|neither\\s+party\\s+shall\\s+assign|shall\\s+not\\s+assign|assign\\s+(and|or)\\s+transfer|successors\\s+and\\s+assigns|assignment\\s+prohibited)\\b"),
                    0.92),
            new ClauseRule("INDEMNITY", Pattern.compile(
                    "(?i)\\b(indemnif(y|ies|ication)|hold\\s+harmless|defend\\s+and\\s+hold\\s+harmless|indemnity\\s+obligations)\\b"),
                    0.93),
            new ClauseRule("TERMINATION", Pattern.compile(
                    "(?i)\\b(termination\\s+for\\s+convenience|termination\\s+for\\s+cause|right\\s+to\\s+terminate|term\\s+and\\s+termination|grounds\\s+for\\s+termination)\\b"),
                    0.91),
            new ClauseRule("GOVERNING_LAW", Pattern.compile(
                    "(?i)\\b(governing\\s+law|jurisdiction\\s+and\\s+venue|choice\\s+of\\s+law|laws\\s+of\\s+the\\s+(republic|state)|submission\\s+to\\s+jurisdiction)\\b"),
                    0.96),
            new ClauseRule("LIMITATION_OF_LIABILITY", Pattern.compile(
                    "(?i)\\b(limitation\\s+of\\s+liability|aggregate\\s+liability|consequential\\s+damages|in\\s+no\\s+event\\s+shall\\s+(either\\s+party|the\\s+company)\\s+be\\s+liable|waiver\\s+of\\s+damages)\\b"),
                    0.93)
    );

    record ClauseRule(String type, Pattern pattern, double baseConfidence) {
    }

    List<ExtractedClause> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        var clauses = new ArrayList<ExtractedClause>();
        for (var rule : RULES) {
            var matcher = rule.pattern().matcher(text);
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                int page = calculatePage(text, start);
                String snippet = extractSnippet(text, start, end);
                double conf = rule.baseConfidence();
                if (snippet.matches("(?i).*(section|article|clause|paragraph)\\s+[0-9IVX]+.*")) {
                    conf = Math.min(0.99, conf + 0.04);
                }
                boolean duplicate = clauses.stream()
                        .anyMatch(c -> c.clauseType().equals(rule.type()) && c.pageNumber() == page);
                if (!duplicate) {
                    clauses.add(new ExtractedClause(rule.type(), conf, page, snippet));
                }
            }
        }
        return clauses;
    }

    private int calculatePage(String text, int offset) {
        int formFeedCount = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\f') {
                formFeedCount++;
            }
        }
        if (formFeedCount > 0) {
            return formFeedCount + 1;
        }
        return Math.max(1, (offset / 3000) + 1);
    }

    private String extractSnippet(String text, int matchStart, int matchEnd) {
        int windowBefore = 100;
        int windowAfter = 250;
        int start = Math.max(0, matchStart - windowBefore);
        int end = Math.min(text.length(), matchEnd + windowAfter);

        while (start > 0 && text.charAt(start) != '\n' && text.charAt(start) != '.') {
            start--;
        }
        if (start < matchStart && (text.charAt(start) == '\n' || text.charAt(start) == '.')) {
            start++;
        }
        while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '.') {
            end++;
        }
        if (end < text.length() && text.charAt(end) == '.') {
            end++;
        }

        String snippet = text.substring(start, end).trim().replaceAll("\\s+", " ");
        if (snippet.length() > 600) {
            snippet = snippet.substring(0, 597) + "...";
        }
        return snippet;
    }
}
