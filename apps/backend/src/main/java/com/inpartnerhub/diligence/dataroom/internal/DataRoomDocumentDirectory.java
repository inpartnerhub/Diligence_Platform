package com.inpartnerhub.diligence.dataroom.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.dataroom.DocumentDirectory;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

@Component
@Transactional(readOnly = true)
class DataRoomDocumentDirectory implements DocumentDirectory {

    private final DocumentRepository documents;
    private final DataRoomAccess access;

    DataRoomDocumentDirectory(DocumentRepository documents, DataRoomAccess access) {
        this.documents = documents;
        this.access = access;
    }

    @Override
    public Optional<DocumentSummary> find(UUID projectId, UUID documentId) {
        return findAll(projectId, List.of(documentId)).stream().findFirst();
    }

    @Override
    public List<DocumentSummary> findAll(UUID projectId, Collection<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return List.of();
        }
        try {
            var visibleFolders = access.visibleFolders(access.project(projectId)).stream()
                    .map(Folder::getId).collect(Collectors.toSet());
            return documents.findAllById(documentIds).stream()
                    .filter(d -> d.getProjectId().equals(projectId) && visibleFolders.contains(d.getFolderId()))
                    .map(d -> new DocumentSummary(d.getId(), d.getFolderId(), d.getName(), d.getContentType(),
                            d.getSizeBytes(), d.getScanStatus().name()))
                    .toList();
        } catch (ResourceNotFoundException | AccessDeniedException ex) {
            return List.of();
        }
    }
}
