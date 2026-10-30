package com.inpartnerhub.diligence.request.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.request.RequestItemDirectory;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

@Component
@Transactional(readOnly = true)
class RequestItemLookup implements RequestItemDirectory {

    private final RequestAccess access;

    RequestItemLookup(RequestAccess access) {
        this.access = access;
    }

    @Override
    public Optional<ItemSummary> find(UUID projectId, UUID itemId) {
        try {
            var item = access.item(access.project(projectId), itemId);
            return Optional.of(new ItemSummary(item.getId(), item.getNumber(), item.getDescription(),
                    item.getWorkstreamId()));
        } catch (ResourceNotFoundException | AccessDeniedException ex) {
            return Optional.empty();
        }
    }
}
