package com.inpartnerhub.diligence.finding;

/** Result of rating a finding: the inputs, the score (impact × likelihood) and the resulting level. */
public record RiskRating(int impact, int likelihood, int score, RiskLevel level) {
}
