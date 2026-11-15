// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.access;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface MembershipRepository extends JpaRepository<Membership, UUID> {

    Optional<Membership> findByUserIdAndOrganizationId(UUID userId, UUID organizationId);

    List<Membership> findByUserId(UUID userId);

    List<Membership> findByOrganizationIdOrderByUserEmailAsc(UUID organizationId);

    long countByRoleId(UUID roleId);
}
