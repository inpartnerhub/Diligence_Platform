package com.inpartnerhub.diligence.dataroom.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.dataroom.RenditionKeys;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Pre-rendered preview pages <em>before</em> watermarking, kept in memory so paging through a document does not read
 * every page from object storage again (DOC-03, PRD Bab 7: preview ≤ 3 s; issue #59). The watermark is still stamped
 * for the viewer on every request, and access is checked before the cache is consulted. Bounded by size (least
 * recently used pages are evicted) and by age; a project's pages are dropped when its data room is purged.
 */
@Component
class PreviewPageCache {

    private static final Logger log = LoggerFactory.getLogger(PreviewPageCache.class);

    private record Key(UUID projectId, UUID versionId, int page) {
    }

    private record Entry(byte[] png, Instant loadedAt) {
    }

    private final DocumentStorage storage;
    private final long maxBytes;
    private final PreviewCacheProperties properties;
    private final Executor prefetcher;
    private final Clock clock;
    private final Counter hits;
    private final Counter misses;
    private final Map<Key, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
    private long bytes;

    @Autowired
    PreviewPageCache(DocumentStorage storage, PreviewCacheProperties properties, MeterRegistry meters) {
        this(storage, properties, meters, Executors.newVirtualThreadPerTaskExecutor(), Clock.systemUTC());
    }

    PreviewPageCache(DocumentStorage storage, PreviewCacheProperties properties, MeterRegistry meters,
            Executor prefetcher, Clock clock) {
        this.storage = storage;
        this.properties = properties;
        this.maxBytes = properties.maxSize().toBytes();
        this.prefetcher = prefetcher;
        this.clock = clock;
        this.hits = Counter.builder("diligence.preview.cache").tag("result", "hit").register(meters);
        this.misses = Counter.builder("diligence.preview.cache").tag("result", "miss").register(meters);
        Gauge.builder("diligence.preview.cache.bytes", this, PreviewPageCache::size).register(meters);
    }

    /**
     * The unwatermarked page image; loads it from object storage on a miss and prefetches the next pages of the
     * version (up to {@code pageCount}) in the background. The caller has checked access to the version.
     */
    byte[] page(UUID projectId, UUID versionId, int page, int pageCount) {
        var key = new Key(projectId, versionId, page);
        var png = cached(key);
        if (png != null) {
            hits.increment();
        } else {
            misses.increment();
            png = load(key);
        }
        for (var next = page + 1; next <= Math.min(pageCount, page + properties.prefetchPages()); next++) {
            var nextKey = new Key(projectId, versionId, next);
            if (cached(nextKey) == null) {
                prefetcher.execute(() -> prefetch(nextKey));
            }
        }
        return png;
    }

    /** Drops every cached page of a project, e.g. when its data room is purged (ADM-06). */
    synchronized void evictProject(UUID projectId) {
        var it = entries.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getKey().projectId().equals(projectId)) {
                bytes -= entry.getValue().png().length;
                it.remove();
            }
        }
    }

    synchronized long size() {
        return bytes;
    }

    private synchronized byte[] cached(Key key) {
        var entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.loadedAt().plus(properties.ttl()).isBefore(Instant.now(clock))) {
            entries.remove(key);
            bytes -= entry.png().length;
            return null;
        }
        return entry.png();
    }

    private byte[] load(Key key) {
        byte[] png;
        try (var content = storage.get(RenditionKeys.page(key.projectId(), key.versionId(), key.page()))) {
            png = content.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        put(key, png);
        return png;
    }

    private void prefetch(Key key) {
        try {
            if (cached(key) == null) {
                load(key);
            }
        } catch (RuntimeException ex) {
            log.debug("Prefetching preview page {} of version {} failed: {}", key.page(), key.versionId(),
                    ex.getMessage());
        }
    }

    private synchronized void put(Key key, byte[] png) {
        if (png.length > maxBytes) {
            return; // larger than the whole cache: serve it, do not keep it
        }
        var previous = entries.put(key, new Entry(png, Instant.now(clock)));
        if (previous != null) {
            bytes -= previous.png().length;
        }
        bytes += png.length;
        var it = entries.entrySet().iterator();
        while (bytes > maxBytes && it.hasNext()) {
            var eldest = it.next();
            bytes -= eldest.getValue().png().length;
            it.remove();
        }
    }
}
