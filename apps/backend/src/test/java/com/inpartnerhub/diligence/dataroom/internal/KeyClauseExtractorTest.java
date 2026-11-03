package com.inpartnerhub.diligence.dataroom.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KeyClauseExtractorTest {

    private final KeyClauseExtractor extractor = new KeyClauseExtractor();

    @Test
    void extractsStandardClauseTypes() {
        String contractText = """
                MASTER SERVICES AGREEMENT
                
                Section 4.1 Change of Control. In the event of a change of control of the Company, the counterparty
                shall have the immediate right to terminate this agreement without penalty.
                
                Section 7.3 Non-Competition. During the term and for 2 years thereafter, the Consultant shall not engage
                in any non-compete activities or compete directly with the business of the Client.
                
                Section 11.2 Assignment. Neither party shall assign or transfer any rights or obligations under this agreement
                without prior written consent of the other party.
                
                Section 12.1 Indemnity. The Contractor agrees to indemnify, defend, and hold harmless the Company
                against any third-party claims or damages.
                
                Section 14.0 Termination. Either party may exercise termination for convenience upon thirty (30) days
                written notice to the other party.
                
                Section 18.5 Governing Law. This agreement shall be governed by and construed in accordance with
                the governing law and jurisdiction of the State of Delaware.
                
                Section 19.1 Limitation of Liability. In no event shall either party be liable for consequential damages,
                and the aggregate liability shall not exceed the fees paid in the prior 12 months.
                """;

        var clauses = extractor.extract(contractText);

        assertThat(clauses).hasSize(7);
        assertThat(clauses).extracting(KeyClauseExtractor.ExtractedClause::clauseType)
                .containsExactlyInAnyOrder(
                        "CHANGE_OF_CONTROL",
                        "NON_COMPETE",
                        "ASSIGNMENT",
                        "INDEMNITY",
                        "TERMINATION",
                        "GOVERNING_LAW",
                        "LIMITATION_OF_LIABILITY"
                );

        var coc = clauses.stream().filter(c -> c.clauseType().equals("CHANGE_OF_CONTROL")).findFirst().orElseThrow();
        assertThat(coc.confidence()).isGreaterThanOrEqualTo(0.95);
        assertThat(coc.extractedText()).contains("change of control");
        assertThat(coc.pageNumber()).isEqualTo(1);
    }

    @Test
    void calculatesPagesWithFormFeeds() {
        String paginatedContract = "Page 1 Content\n\fPage 2 has Section 5.1 Non-Compete and non-competition obligations\n\fPage 3 Content";

        var clauses = extractor.extract(paginatedContract);

        assertThat(clauses).hasSize(1);
        var clause = clauses.get(0);
        assertThat(clause.clauseType()).isEqualTo("NON_COMPETE");
        assertThat(clause.pageNumber()).isEqualTo(2);
    }

    @Test
    void handlesBlankText() {
        assertThat(extractor.extract("")).isEmpty();
        assertThat(extractor.extract(null)).isEmpty();
        assertThat(extractor.extract("   \n\t ")).isEmpty();
    }
}
