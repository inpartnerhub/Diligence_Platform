package com.inpartnerhub.diligence.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CsvCellsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "=1+1", "+1+1", "-1+1", "@SUM(A1)", "|calc", "=cmd|' /C calc'!A0",
            " =1+1", "   @SUM(A1)", " =1+1", "　+1",
            "\t=1+1", "\tplain", "\rplain", "\nplain", "\r\n=1",
            "＝1+1", "＋1", "－1", "＠SUM(A1)"
    })
    void neutralisesValuesASpreadsheetWouldEvaluate(String value) {
        assertThat(CsvCells.cell(value)).isEqualTo("\"'" + value + "\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Due diligence", "1+1", "a=b", "user@example.com", "2026-11-09", " plain", "   "})
    void leavesOrdinaryValuesAlone(String value) {
        assertThat(CsvCells.cell(value)).isEqualTo("\"" + value + "\"");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void writesAnEmptyQuotedCellForMissingValues(String value) {
        assertThat(CsvCells.cell(value)).isEqualTo("\"\"");
    }

    @Test
    void doublesQuotesAfterNeutralising() {
        assertThat(CsvCells.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvCells.cell("=\"x\"")).isEqualTo("\"'=\"\"x\"\"\"");
    }
}
