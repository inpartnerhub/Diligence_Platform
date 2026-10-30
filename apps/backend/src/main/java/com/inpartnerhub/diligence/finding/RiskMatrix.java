package com.inpartnerhub.diligence.finding;

/**
 * Impact × likelihood matrix (both 1–5, PRD FND-03). A score at or above a threshold reaches that level.
 * Thresholds are configurable by administrators; {@link #DEFAULT} implements Appendix A:
 * 1–4 low, 5–9 medium, 10–16 high, 17–25 critical.
 */
public record RiskMatrix(int mediumFrom, int highFrom, int criticalFrom) {

    public static final int MIN_SCALE = 1;
    public static final int MAX_SCALE = 5;
    public static final RiskMatrix DEFAULT = new RiskMatrix(5, 10, 17);

    public RiskMatrix {
        if (!(MIN_SCALE < mediumFrom && mediumFrom < highFrom && highFrom < criticalFrom
                && criticalFrom <= MAX_SCALE * MAX_SCALE)) {
            throw new IllegalArgumentException("Thresholds must satisfy 1 < medium < high < critical <= 25, got "
                    + mediumFrom + "/" + highFrom + "/" + criticalFrom);
        }
    }

    public RiskRating rate(int impact, int likelihood) {
        requireScale("impact", impact);
        requireScale("likelihood", likelihood);
        var score = impact * likelihood;
        return new RiskRating(impact, likelihood, score, levelFor(score));
    }

    private RiskLevel levelFor(int score) {
        if (score >= criticalFrom) {
            return RiskLevel.CRITICAL;
        }
        if (score >= highFrom) {
            return RiskLevel.HIGH;
        }
        return score >= mediumFrom ? RiskLevel.MEDIUM : RiskLevel.LOW;
    }

    private static void requireScale(String name, int value) {
        if (value < MIN_SCALE || value > MAX_SCALE) {
            throw new IllegalArgumentException(name + " must be between 1 and 5, got " + value);
        }
    }
}
