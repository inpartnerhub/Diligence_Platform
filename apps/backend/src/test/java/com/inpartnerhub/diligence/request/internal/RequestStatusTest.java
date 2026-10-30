package com.inpartnerhub.diligence.request.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.inpartnerhub.diligence.project.WorkstreamType;

class RequestStatusTest {

    @Test
    void targetOnlyReportsDeliveryOnOpenItems() {
        assertThat(RequestStatus.REQUESTED.targetMayMoveTo(RequestStatus.PARTIAL)).isTrue();
        assertThat(RequestStatus.REJECTED.targetMayMoveTo(RequestStatus.RECEIVED)).isTrue();
        assertThat(RequestStatus.PARTIAL.targetMayMoveTo(RequestStatus.PARTIAL)).isTrue();
        assertThat(RequestStatus.NOT_REQUESTED.targetMayMoveTo(RequestStatus.RECEIVED)).isFalse();
        assertThat(RequestStatus.RECEIVED.targetMayMoveTo(RequestStatus.PARTIAL)).isFalse();
        assertThat(RequestStatus.REQUESTED.targetMayMoveTo(RequestStatus.CLOSED)).isFalse();
        assertThat(RequestStatus.CLOSED.acceptsDeliveries()).isFalse();
        assertThat(RequestStatus.NOT_REQUESTED.acceptsDeliveries()).isFalse();
        assertThat(RequestStatus.RECEIVED.acceptsDeliveries()).isTrue();
    }

    @Test
    void internalWorkflow() {
        assertThat(RequestStatus.NOT_REQUESTED.internalMayMoveTo(RequestStatus.REQUESTED)).isTrue();
        assertThat(RequestStatus.NOT_REQUESTED.internalMayMoveTo(RequestStatus.RECEIVED)).isFalse();
        assertThat(RequestStatus.RECEIVED.internalMayMoveTo(RequestStatus.REJECTED)).isTrue();
        assertThat(RequestStatus.CLOSED.internalMayMoveTo(RequestStatus.RECEIVED)).isTrue();
        assertThat(RequestStatus.CLOSED.internalMayMoveTo(RequestStatus.CLOSED)).isFalse();
    }

    @Test
    void numbersFollowTheWorkstreamPrefix() {
        assertThat(RequestNumbers.next(WorkstreamType.LEGAL, List.of())).isEqualTo("LEG-001");
        assertThat(RequestNumbers.next(WorkstreamType.LEGAL, List.of("LEG-001", "LEG-009", "LEG-X", "TAX-020")))
                .isEqualTo("LEG-010");
        assertThat(RequestNumbers.next(WorkstreamType.IT_CYBER, List.of("ITC-999"))).isEqualTo("ITC-1000");
        assertThat(RequestNumbers.isValid("A-1.2_b")).isTrue();
        assertThat(RequestNumbers.isValid("-bad")).isFalse();
        assertThat(RequestNumbers.isValid("with space")).isFalse();
        assertThat(RequestNumbers.parseWorkstream("it cyber")).contains(WorkstreamType.IT_CYBER);
        assertThat(RequestNumbers.parseWorkstream("leg")).contains(WorkstreamType.LEGAL);
        assertThat(RequestNumbers.parseWorkstream("esg-compliance")).contains(WorkstreamType.ESG_COMPLIANCE);
        assertThat(RequestNumbers.parseWorkstream("unknown")).isEmpty();
        assertThat(RequestNumbers.parseWorkstream(" ")).isEmpty();
    }
}
