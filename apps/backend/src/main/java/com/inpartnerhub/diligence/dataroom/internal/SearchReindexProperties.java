package com.inpartnerhub.diligence.dataroom.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Bulk re-index of the search index (issue #80).
 *
 * @param batchSize    documents read from PostgreSQL per query
 * @param bulkMaxSize  largest bulk request to OpenSearch (extracted text is up to 500,000 characters per document)
 * @param parallelism  extracted texts read from object storage at the same time
 * @param staleAfter   a running run whose heartbeat is older than this is considered interrupted (pod gone)
 */
@ConfigurationProperties("diligence.dataroom.reindex")
record SearchReindexProperties(Integer batchSize, DataSize bulkMaxSize, Integer parallelism, Duration staleAfter) {

    SearchReindexProperties {
        batchSize = batchSize == null || batchSize < 1 ? 500 : batchSize;
        bulkMaxSize = bulkMaxSize == null || bulkMaxSize.toBytes() < 1 ? DataSize.ofMegabytes(20) : bulkMaxSize;
        parallelism = parallelism == null || parallelism < 1 ? 16 : parallelism;
        staleAfter = staleAfter == null || staleAfter.isNegative() || staleAfter.isZero() ? Duration.ofMinutes(10)
                : staleAfter;
    }
}
