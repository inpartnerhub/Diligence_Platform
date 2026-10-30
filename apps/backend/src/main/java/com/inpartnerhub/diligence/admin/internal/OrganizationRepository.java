package com.inpartnerhub.diligence.admin.internal;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    boolean existsByNameIgnoreCase(String name);
}
