package com.inpartnerhub.diligence.dataroom.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.project.ProjectEvents.ProjectDataPurgeRequested;
import com.inpartnerhub.diligence.project.ProjectEvents.ProjectDataPurged;
import com.inpartnerhub.diligence.search.SearchIndex;

/**
 * Handles project data room purging upon retention expiry or administrator request (PRD ADM-06, UU PDP).
 * Removes the S3 objects (every version), the full-text search entries and the PostgreSQL data room rows. If any
 * step fails the listener fails, so the purge is retried and never recorded as complete while data remains (#124).
 */
@Component
class DataRoomRetentionListener {

    private static final Logger log = LoggerFactory.getLogger(DataRoomRetentionListener.class);

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final FolderRepository folders;
    private final DocumentStorage storage;
    private final SearchIndex searchIndex;
    private final PreviewPageCache previewPages;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnly;

    DataRoomRetentionListener(DocumentRepository documents, DocumentVersionRepository versions,
            FolderRepository folders, DocumentStorage storage, SearchIndex searchIndex,
            PreviewPageCache previewPages, ApplicationEventPublisher events, PlatformTransactionManager txManager) {
        this.documents = documents;
        this.versions = versions;
        this.folders = folders;
        this.storage = storage;
        this.searchIndex = searchIndex;
        this.previewPages = previewPages;
        this.events = events;
        this.transactions = new TransactionTemplate(txManager);
        this.readOnly = new TransactionTemplate(txManager);
        readOnly.setReadOnly(true);
    }

    /**
     * Runs outside a transaction ({@code NOT_SUPPORTED}, #123): the S3 and OpenSearch calls can take long for a large
     * data room and must not hold a database connection meanwhile. The database work happens in two short
     * transactions: counting first, deleting (and publishing {@link ProjectDataPurged}) last.
     */
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(ProjectDataPurgeRequested event) {
        var projectId = event.projectId();
        log.info("Purging data room for project {} ({})", event.code(), projectId);

        // Counted, not loaded (#123): a data room can hold hundreds of thousands of documents.
        var counts = readOnly.execute(status -> new long[] { documents.countByProjectId(projectId),
                versions.countByProjectId(projectId) });

        // Failures are not swallowed (#124): an exception leaves the event publication incomplete, so the failed-event
        // retry job (#81) runs the purge again and no ProjectDataPurged audit entry claims a purge while files or
        // index entries remain. Every step is idempotent.
        // 1. Remove the project's entries from the full-text search index, in one request
        searchIndex.removeProject(projectId);

        // 2. Delete every version of the binary objects under "projects/{projectId}/"
        long s3ObjectsPurged = storage.deletePrefix("projects/" + projectId + "/");

        // Purged pages must not linger in memory either.
        previewPages.evictProject(projectId);

        // 3. Delete documents (cascading versions, annotations and clauses) and then folders (cascading grants), one
        // statement each; the audit event is published in the same transaction.
        transactions.executeWithoutResult(status -> {
            documents.deleteAllForProject(projectId);
            folders.deleteAllForProject(projectId);
            events.publishEvent(new ProjectDataPurged(projectId, event.code(), counts[0], counts[1],
                    s3ObjectsPurged, event.requestMetadata()));
        });

        log.info("Purged project {} data room: {} documents, {} versions, {} S3 objects",
                event.code(), counts[0], counts[1], s3ObjectsPurged);
    }
}

