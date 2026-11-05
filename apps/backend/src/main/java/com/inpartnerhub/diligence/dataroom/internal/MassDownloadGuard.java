package com.inpartnerhub.diligence.dataroom.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.dataroom.DataRoomEvents.MassDownloadDetected;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.StepUpRequiredException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Controls against mass document exfiltration (DOC-05, PRD Bab 10.1, issue #62):
 * <ul>
 * <li>large bulk downloads need a recent sign-in with MFA (step-up), so a stolen session alone cannot take the data
 * room;</li>
 * <li>documents downloaded per user are counted in Redis (shared by every replica) in fixed windows; crossing the
 * threshold is audited and counted as {@code diligence_dataroom_mass_download_total} for the alert.</li>
 * </ul>
 * Counting fails open: when Redis is unavailable downloads continue (they are still audited one by one) and Redis is
 * skipped for a minute.
 */
@Component
class MassDownloadGuard {

    private static final Logger log = LoggerFactory.getLogger(MassDownloadGuard.class);
    private static final Duration REDIS_BACKOFF = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final CurrentUserProvider currentUser;
    private final ApplicationEventPublisher events;
    private final MassDownloadProperties properties;
    private final Clock clock;
    private final Counter detected;
    private volatile Instant redisRetryAt = Instant.MIN;

    @Autowired
    MassDownloadGuard(StringRedisTemplate redis, CurrentUserProvider currentUser, ApplicationEventPublisher events,
            MassDownloadProperties properties, MeterRegistry meters) {
        this(redis, currentUser, events, properties, meters, Clock.systemUTC());
    }

    MassDownloadGuard(StringRedisTemplate redis, CurrentUserProvider currentUser, ApplicationEventPublisher events,
            MassDownloadProperties properties, MeterRegistry meters, Clock clock) {
        this.redis = redis;
        this.currentUser = currentUser;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
        this.detected = Counter.builder("diligence.dataroom.mass.download")
                .description("Users who downloaded more documents than the threshold within the window (#62)")
                .register(meters);
    }

    /** Bulk downloads of {@code documents} or more need a sign-in within the step-up age. */
    void requireRecentSignIn(int documents) {
        if (documents < properties.stepUpFiles()) {
            return;
        }
        var maxAge = properties.stepUpMaxAge();
        var signedIn = currentUser.authenticatedAt();
        if (signedIn.isEmpty() || signedIn.get().plus(maxAge).isBefore(Instant.now(clock))) {
            throw new StepUpRequiredException("Sign in again to confirm downloading %d documents at once."
                    .formatted(documents), maxAge);
        }
    }

    /** Counts downloaded documents for the current user; flags the user once per window above the threshold. */
    void record(UUID projectId, int documents) {
        if (documents <= 0) {
            return;
        }
        var user = currentUser.currentUser().orElse(null);
        var now = Instant.now(clock);
        if (user == null || now.isBefore(redisRetryAt)) {
            return;
        }
        var window = properties.alertWindow();
        var bucket = now.getEpochSecond() / window.toSeconds();
        var key = "diligence:downloads:%s:%d".formatted(user.id(), bucket);
        long total;
        try {
            var value = redis.opsForValue().increment(key, documents);
            redis.expire(key, window.multipliedBy(2));
            total = value == null ? documents : value;
        } catch (RuntimeException ex) {
            redisRetryAt = now.plus(REDIS_BACKOFF);
            log.warn("Mass-download counting unavailable for {}: {}", REDIS_BACKOFF, ex.getMessage());
            return;
        }
        var threshold = properties.alertDocuments();
        if (total - documents < threshold && total >= threshold) {
            detected.increment();
            log.warn("User {} downloaded {} documents within {} (threshold {})", user.id(), total, window,
                    threshold);
            events.publishEvent(new MassDownloadDetected(projectId, user.id(), total, window.toMinutes(),
                    RequestMetadata.capture()));
        }
    }
}
