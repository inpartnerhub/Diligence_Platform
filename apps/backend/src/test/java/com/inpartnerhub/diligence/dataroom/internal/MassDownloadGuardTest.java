package com.inpartnerhub.diligence.dataroom.internal;

import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.inpartnerhub.diligence.dataroom.DataRoomEvents.MassDownloadDetected;
import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.StepUpRequiredException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Issue #62: step-up for large bulk downloads and per-user mass-download detection. */
class MassDownloadGuardTest {

    private static final Instant NOW = Instant.parse("2026-11-06T03:00:00Z");
    private static final UUID USER = UUID.randomUUID();

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MassDownloadGuard guard = new MassDownloadGuard(redis, currentUser, events,
            new MassDownloadProperties(50, Duration.ofMinutes(10), 200, Duration.ofMinutes(15)), meters,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(currentUser.currentUser()).thenReturn(Optional.of(
                new AuthenticatedUser(USER, "u", "u@x.test", "U", Set.of(Role.ANALYST))));
    }

    @Test
    void largeBulkDownloadsNeedARecentSignIn() {
        guard.requireRecentSignIn(49); // below the threshold: no check at all
        verify(currentUser, never()).authenticatedAt();

        when(currentUser.authenticatedAt()).thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(5))));
        guard.requireRecentSignIn(50);

        when(currentUser.authenticatedAt()).thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(11))));
        assertThatThrownBy(() -> guard.requireRecentSignIn(120))
                .isInstanceOf(StepUpRequiredException.class)
                .hasMessageContaining("120 documents")
                .satisfies(ex -> assertThat(((StepUpRequiredException) ex).maxAge()).hasMinutes(10));

        when(currentUser.authenticatedAt()).thenReturn(Optional.empty()); // token without auth_time
        assertThatThrownBy(() -> guard.requireRecentSignIn(50)).isInstanceOf(StepUpRequiredException.class);
    }

    @Test
    void flagsAUserOncePerWindowWhenCrossingTheThreshold() {
        var key = "diligence:downloads:%s:%d".formatted(USER, NOW.getEpochSecond() / Duration.ofMinutes(15).toSeconds());
        when(values.increment(key, 150L)).thenReturn(150L);
        when(values.increment(key, 60L)).thenReturn(210L);
        when(values.increment(key, 1L)).thenReturn(211L);

        guard.record(PROJECT, 150);
        verify(events, never()).publishEvent(any(Object.class));

        guard.record(PROJECT, 60); // 150 → 210 crosses 200
        var event = ArgumentCaptor.forClass(MassDownloadDetected.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().userId()).isEqualTo(USER);
        assertThat(event.getValue().documents()).isEqualTo(210);
        assertThat(event.getValue().auditAction()).isEqualTo("document.mass-download-detected");

        guard.record(PROJECT, 1); // already flagged in this window
        verify(events, times(1)).publishEvent(any(Object.class));
        verify(redis, times(3)).expire(key, Duration.ofMinutes(30));
        assertThat(meters.counter("diligence.dataroom.mass.download").count()).isEqualTo(1);
    }

    @Test
    void failsOpenAndBacksOffWhenRedisIsDown() {
        when(values.increment(anyString(), anyLong())).thenThrow(new RedisConnectionFailureException("down"));

        guard.record(PROJECT, 300);
        guard.record(PROJECT, 300); // skipped during the back-off, no second connection attempt

        verify(values, times(1)).increment(anyString(), anyLong());
        verify(events, never()).publishEvent(any(Object.class));
    }
}
