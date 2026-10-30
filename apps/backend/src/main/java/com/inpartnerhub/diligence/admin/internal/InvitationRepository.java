package com.inpartnerhub.diligence.admin.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    List<Invitation> findByUserIdAndStatus(UUID userId, Invitation.Status status);

    Optional<Invitation> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);
}
