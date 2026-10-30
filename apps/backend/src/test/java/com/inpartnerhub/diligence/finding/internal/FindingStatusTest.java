package com.inpartnerhub.diligence.finding.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.inpartnerhub.diligence.project.WorkstreamType;

class FindingStatusTest {

    @Test
    void workflowAndReasons() {
        assertThat(FindingStatus.DRAFT.mayMoveTo(FindingStatus.IN_REVIEW)).isTrue();
        assertThat(FindingStatus.DRAFT.mayMoveTo(FindingStatus.APPROVED)).isFalse();
        assertThat(FindingStatus.IN_REVIEW.mayMoveTo(FindingStatus.APPROVED)).isTrue();
        assertThat(FindingStatus.APPROVED.mayMoveTo(FindingStatus.RELEASED)).isTrue();
        assertThat(FindingStatus.RELEASED.mayMoveTo(FindingStatus.DRAFT)).isFalse();
        assertThat(FindingStatus.RELEASED.mayMoveTo(FindingStatus.ARCHIVED)).isTrue();
        assertThat(FindingStatus.ARCHIVED.mayMoveTo(FindingStatus.DRAFT)).isFalse();
        assertThat(FindingStatus.IN_REVIEW.needsReason(FindingStatus.DRAFT)).isTrue();
        assertThat(FindingStatus.DRAFT.needsReason(FindingStatus.ARCHIVED)).isTrue();
        assertThat(FindingStatus.IN_REVIEW.needsReason(FindingStatus.APPROVED)).isFalse();
        assertThat(FindingStatus.DRAFT.editable()).isTrue();
        assertThat(FindingStatus.APPROVED.editable()).isFalse();
    }

    @Test
    void numbersFollowTheWorkstream() {
        assertThat(FindingNumbers.next(WorkstreamType.LEGAL, List.of())).isEqualTo("F-LEG-001");
        assertThat(FindingNumbers.next(WorkstreamType.TAX, List.of("F-TAX-007", "F-TAX-x", "F-LEG-020")))
                .isEqualTo("F-TAX-008");
    }
}
