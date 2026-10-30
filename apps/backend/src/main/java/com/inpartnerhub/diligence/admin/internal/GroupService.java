package com.inpartnerhub.diligence.admin.internal;

import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

@Service
@Transactional
class GroupService {

    private final UserGroupRepository groups;
    private final UserAccountRepository users;

    GroupService(UserGroupRepository groups, UserAccountRepository users) {
        this.groups = groups;
        this.users = users;
    }

    @Transactional(readOnly = true)
    java.util.List<UserGroup> list() {
        return groups.findAll(org.springframework.data.domain.Sort.by("name"));
    }

    UserGroup create(String name, String description, UUID organizationId) {
        var trimmed = name.strip();
        if (groups.existsByNameIgnoreCase(trimmed)) {
            throw new ResourceConflictException("A group named '" + trimmed + "' already exists");
        }
        return groups.save(new UserGroup(trimmed, description, organizationId));
    }

    UserGroup replaceMembers(UUID groupId, Set<UUID> userIds) {
        var group = groups.findById(groupId).orElseThrow(() -> new ResourceNotFoundException("Group", groupId));
        var unknown = userIds.stream().filter(id -> !users.existsById(id)).toList();
        if (!unknown.isEmpty()) {
            throw new ResourceNotFoundException("User", unknown.getFirst());
        }
        group.replaceMembers(userIds);
        return group;
    }
}
