package com.inpartnerhub.diligence.finding.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import com.inpartnerhub.diligence.finding.FindingEvents.RiskMatrixChanged;
import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;

class RiskMatrixServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RiskMatrixService service = new RiskMatrixService(jdbc, currentUser, events);

    @Test
    void updatesThresholdsReRatesOpenFindingsAndAudits() {
        var admin = UUID.randomUUID();
        when(currentUser.requireCurrentUser()).thenReturn(new AuthenticatedUser(admin, "a", "a@x.test", "A",
                Set.of(Role.SUPER_ADMIN)));
        when(jdbc.queryForObject("select fnd.recompute_risk_levels()", Integer.class)).thenReturn(7);

        assertThat(service.update(4, 9, 16)).isEqualTo(7);

        verify(jdbc).update(anyString(), eq(4), eq(9), eq(16), eq(admin));
        var event = ArgumentCaptor.forClass(RiskMatrixChanged.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().rerated()).isEqualTo(7);
        assertThat(event.getValue().auditDetails()).containsEntry("highFrom", "9");
    }

    @Test
    void rejectsInconsistentThresholds() {
        assertThatThrownBy(() -> service.update(10, 9, 16)).isInstanceOf(InvalidRequestException.class);
        verify(events, never()).publishEvent(any());
    }
}
