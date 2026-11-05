package com.inpartnerhub.diligence.dataroom.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Cache of pre-rendered preview pages before watermarking (DOC-03, issue #59).
 *
 * @param maxSize       memory the cached page images may use per backend instance
 * @param ttl           how long a page stays cached after it was loaded
 * @param prefetchPages pages after the requested one loaded in the background
 */
@ConfigurationProperties("diligence.dataroom.preview-cache")
record PreviewCacheProperties(DataSize maxSize, Duration ttl, Integer prefetchPages) {

    PreviewCacheProperties {
        maxSize = maxSize == null ? DataSize.ofMegabytes(64) : maxSize;
        ttl = ttl == null || ttl.isNegative() ? Duration.ofMinutes(30) : ttl;
        prefetchPages = prefetchPages == null || prefetchPages < 0 ? 2 : prefetchPages;
    }
}
