package com.inpartnerhub.diligence.admin.internal;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface UserGroupRepository extends JpaRepository<UserGroup, UUID> {

    boolean existsByNameIgnoreCase(String name);
}
