package com.inpartnerhub.diligence.dataroom.internal;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.search.SearchIndex;
import com.inpartnerhub.diligence.search.SearchIndex.SearchHit;
import com.inpartnerhub.diligence.search.SearchIndex.SearchQuery;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;

/**
 * Full-text search in a project's data room (PRD DOC-06). The query is restricted to the folders the caller can see
 * (row-level security plus analyst workstream scope), and each hit is re-checked against the database, so the index
 * can never reveal a document across the information barrier.
 */
@Service
@Transactional(readOnly = true)
class DocumentSearchService {

    static final int MAX_PAGE_SIZE = 50;

    record Result(Document document, SearchHit hit) {
    }

    record ResultPage(List<Result> results, long total) {
    }

    private final DataRoomAccess access;
    private final DocumentRepository documents;
    private final SearchIndex index;

    DocumentSearchService(DataRoomAccess access, DocumentRepository documents, SearchIndex index) {
        this.access = access;
        this.documents = documents;
        this.index = index;
    }

    ResultPage search(UUID projectId, String text, int page, int size) {
        if (text == null || text.isBlank() || text.length() > 500) {
            throw new InvalidRequestException("Enter a search term of at most 500 characters");
        }
        var context = access.project(projectId);
        var folderIds = access.visibleFolders(context).stream().map(Folder::getId).toList();
        if (folderIds.isEmpty()) {
            return new ResultPage(List.of(), 0);
        }
        var found = index.search(new SearchQuery(projectId, folderIds, text, Math.max(page, 0),
                Math.clamp(size, 1, MAX_PAGE_SIZE)));
        var visible = new HashMap<UUID, Document>();
        documents.findAllById(found.hits().stream().map(SearchHit::documentId).toList())
                .forEach(d -> visible.put(d.getId(), d));
        var results = found.hits().stream()
                .filter(hit -> visible.containsKey(hit.documentId())
                        && visible.get(hit.documentId()).getScanStatus() == ScanStatus.CLEAN
                        && folderIds.contains(visible.get(hit.documentId()).getFolderId()))
                .map(hit -> new Result(visible.get(hit.documentId()), hit))
                .toList();
        return new ResultPage(results, found.total());
    }
}
