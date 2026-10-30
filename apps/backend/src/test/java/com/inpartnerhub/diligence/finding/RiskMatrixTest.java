package com.inpartnerhub.diligence.finding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** PRD Appendix A: 1–4 low, 5–9 medium, 10–16 high, 17–25 critical. */
class RiskMatrixTest {

    @ParameterizedTest
    @CsvSource({
            "1,1,1,LOW", "2,2,4,LOW", "1,5,5,MEDIUM", "3,3,9,MEDIUM", "2,5,10,HIGH",
            "4,4,16,HIGH", "3,5,15,HIGH", "5,4,20,CRITICAL", "5,5,25,CRITICAL"
    })
    void defaultMatrixFollowsAppendixA(int impact, int likelihood, int score, RiskLevel level) {
        var rating = RiskMatrix.DEFAULT.rate(impact, likelihood);

        assertThat(rating.score()).isEqualTo(score);
        assertThat(rating.level()).isEqualTo(level);
        assertThat(rating.impact()).isEqualTo(impact);
        assertThat(rating.likelihood()).isEqualTo(likelihood);
    }

    @Test
    void customThresholdsAreApplied() {
        var strict = new RiskMatrix(3, 6, 12);

        assertThat(strict.rate(3, 4).level()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(strict.rate(2, 3).level()).isEqualTo(RiskLevel.HIGH);
        assertThat(strict.rate(1, 2).level()).isEqualTo(RiskLevel.LOW);
    }

    @ParameterizedTest
    @CsvSource({ "0,3", "6,1", "3,0", "3,6" })
    void rejectsValuesOutsideTheScale(int impact, int likelihood) {
        assertThatIllegalArgumentException().isThrownBy(() -> RiskMatrix.DEFAULT.rate(impact, likelihood));
    }

    @ParameterizedTest
    @CsvSource({ "1,10,17", "10,10,17", "5,17,17", "5,10,26" })
    void rejectsInconsistentThresholds(int medium, int high, int critical) {
        assertThatIllegalArgumentException().isThrownBy(() -> new RiskMatrix(medium, high, critical));
    }
}
