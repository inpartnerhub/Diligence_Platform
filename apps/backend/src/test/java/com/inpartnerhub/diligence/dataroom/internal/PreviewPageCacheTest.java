package com.inpartnerhub.diligence.dataroom.internal;

import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.dataroom.RenditionKeys;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Issue #59: preview pages are cached before watermarking, bounded by size and age, and prefetched ahead. */
class PreviewPageCacheTest {

    private final DocumentStorage storage = mock(DocumentStorage.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<Runnable> background = new ArrayList<>();
    private final UUID version = UUID.randomUUID();
    private Instant now = Instant.parse("2026-11-06T02:00:00Z");

    private PreviewPageCache cache(DataSize maxSize, int prefetch) {
        var clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        return new PreviewPageCache(storage, new PreviewCacheProperties(maxSize, Duration.ofMinutes(30), prefetch),
                meters, background::add, clock);
    }

    private void stored(int page, int size) {
        when(storage.get(RenditionKeys.page(PROJECT, version, page)))
                .thenAnswer(inv -> new ByteArrayInputStream(new byte[size]));
    }

    @Test
    void servesRepeatedPagesFromMemoryAndPrefetchesTheNextOnes() {
        for (var page = 1; page <= 4; page++) {
            stored(page, 100);
        }
        var cache = cache(DataSize.ofKilobytes(64), 2);

        assertThat(cache.page(PROJECT, version, 1, 3)).hasSize(100);
        assertThat(background).hasSize(2); // pages 2 and 3, never beyond the page count
        background.forEach(Runnable::run);
        background.clear();

        cache.page(PROJECT, version, 1, 3);
        cache.page(PROJECT, version, 2, 3);
        cache.page(PROJECT, version, 3, 3);
        assertThat(background).isEmpty(); // everything ahead is already cached
        verify(storage, times(1)).get(RenditionKeys.page(PROJECT, version, 1));
        verify(storage, times(1)).get(RenditionKeys.page(PROJECT, version, 2));
        verify(storage, never()).get(RenditionKeys.page(PROJECT, version, 4));
        assertThat(meters.counter("diligence.preview.cache", "result", "hit").count()).isEqualTo(3);
        assertThat(meters.counter("diligence.preview.cache", "result", "miss").count()).isEqualTo(1);
        assertThat(cache.size()).isEqualTo(300);
    }

    @Test
    void evictsLeastRecentlyUsedPagesAndExpiredOnes() {
        for (var page = 1; page <= 3; page++) {
            stored(page, 400);
        }
        var cache = cache(DataSize.ofBytes(1000), 0);

        cache.page(PROJECT, version, 1, 3);
        cache.page(PROJECT, version, 2, 3);
        cache.page(PROJECT, version, 1, 3); // page 1 is now the most recently used
        cache.page(PROJECT, version, 3, 3); // 1200 bytes > 1000: page 2 goes
        assertThat(cache.size()).isEqualTo(800);
        cache.page(PROJECT, version, 1, 3);
        verify(storage, times(1)).get(RenditionKeys.page(PROJECT, version, 1));
        cache.page(PROJECT, version, 2, 3);
        verify(storage, times(2)).get(RenditionKeys.page(PROJECT, version, 2));

        now = now.plus(Duration.ofMinutes(31));
        cache.page(PROJECT, version, 1, 3);
        verify(storage, times(2)).get(RenditionKeys.page(PROJECT, version, 1));
    }

    @Test
    void dropsPurgedProjectsAndNeverKeepsOversizedPages() {
        stored(1, 100);
        stored(2, 5000);
        var cache = cache(DataSize.ofBytes(1000), 0);

        cache.page(PROJECT, version, 1, 2);
        assertThat(cache.page(PROJECT, version, 2, 2)).hasSize(5000);
        assertThat(cache.size()).isEqualTo(100);

        cache.evictProject(PROJECT);
        assertThat(cache.size()).isZero();
        cache.page(PROJECT, version, 1, 2);
        verify(storage, times(2)).get(RenditionKeys.page(PROJECT, version, 1));
    }
}
