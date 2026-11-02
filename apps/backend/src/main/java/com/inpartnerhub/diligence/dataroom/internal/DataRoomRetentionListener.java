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
 * Safely removes S3 objects, full-text search index documents, and PostgreSQL data room entities.
 */
@Component
class DataRoomRetentionListener {

    private static final Logger log = LoggerFactory.getLogger(DataRoomRetentionListener.class);

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final FolderRepository folders;
    private final DocumentStorage storage;
    private final SearchIndex searchIndex;
    private final ApplicationEventPublisher events;

    DataRoomRetentionListener(DocumentRepository documents, DocumentVersionRepository versions,
            FolderRepository folders, DocumentStorage storage, SearchIndex searchIndex,
            ApplicationEventPublisher events) {
        this.documents = documents;
        this.versions = versions;
        this.folders = folders;
        this.storage = storage;
        this.searchIndex = searchIndex;
        this.events = events;
    }

    @ApplicationModuleListener
    void on(ProjectDataPurgeRequested event) {
        var projectId = event.projectId();
        log.info("Purging data room for project {} ({})", event.code(), projectId);

        var docList = documents.findByProjectId(projectId);
        long documentsCount = docList.size();

        // 1. Remove from full-text search index
        for (var doc : docList) {
            try {
                searchIndex.remove(doc.getId());
            } catch (Exception ex) {
                log.warn("Failed to remove document {} from search index: {}", doc.getId(), ex.getMessage());
            }
        }

        // 2. Count versions
        var verList = versions.findByProjectId(projectId);
        long versionsCount = verList.size();

        // 3. Delete binary objects from S3 under "projects/{projectId}/"
        long s3ObjectsPurged = 0;
        try {
            s3ObjectsPurged = storage.deletePrefix("projects/" + projectId + "/");
        } catch (Exception ex) {
            log.warn("Failed to delete S3 objects for project {}: {}", projectId, ex.getMessage());
        }

        // 4. Delete documents (cascades versions and annotations) and folders (cascades folder accesses)
        documents.deleteByProjectId(projectId);
        folders.deleteByProjectId(projectId);

        log.info("Purged project {} data room: {} documents, {} versions, {} S3 objects",
                event.code(), documentsCount, versionsCount, s3ObjectsPurged);

        events.publishEvent(new ProjectDataPurged(projectId, event.code(), documentsCount, versionsCount,
                s3ObjectsPurged, event.requestMetadata()));
    }
}

