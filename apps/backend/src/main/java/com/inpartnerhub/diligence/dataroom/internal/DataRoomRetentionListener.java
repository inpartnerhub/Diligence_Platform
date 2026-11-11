package com.inpartnerhub.diligence.dataroom.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

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

    DataRoomRetentionListener(DocumentRepository documents, DocumentVersionRepository versions,
            FolderRepository folders, DocumentStorage storage, SearchIndex searchIndex,
            PreviewPageCache previewPages, ApplicationEventPublisher events) {
        this.documents = documents;
        this.versions = versions;
        this.folders = folders;
        this.storage = storage;
        this.searchIndex = searchIndex;
        this.previewPages = previewPages;
        this.events = events;
    }

    @ApplicationModuleListener
    void on(ProjectDataPurgeRequested event) {
        var projectId = event.projectId();
        log.info("Purging data room for project {} ({})", event.code(), projectId);

        var docList = documents.findByProjectId(projectId);
        long documentsCount = docList.size();

        // Failures are not swallowed (#124): an exception rolls this transaction back and leaves the event publication
        // incomplete, so the failed-event retry job (#81) runs the purge again and no ProjectDataPurged audit entry
        // claims a purge while files or index entries remain. Both deletions are idempotent.
        // 1. Remove from full-text search index
        for (var doc : docList) {
            searchIndex.remove(doc.getId());
        }

        // 2. Count versions
        var verList = versions.findByProjectId(projectId);
        long versionsCount = verList.size();

        // 3. Delete every version of the binary objects under "projects/{projectId}/"
        long s3ObjectsPurged = storage.deletePrefix("projects/" + projectId + "/");

        // Purged pages must not linger in memory either.
        previewPages.evictProject(projectId);

        // 4. Delete documents (cascades versions and annotations) and folders (cascades folder accesses)
        documents.deleteByProjectId(projectId);
        folders.deleteByProjectId(projectId);

        log.info("Purged project {} data room: {} documents, {} versions, {} S3 objects",
                event.code(), documentsCount, versionsCount, s3ObjectsPurged);

        events.publishEvent(new ProjectDataPurged(projectId, event.code(), documentsCount, versionsCount,
                s3ObjectsPurged, event.requestMetadata()));
    }
}

