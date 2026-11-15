// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.access;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ManagerUserRepository extends JpaRepository<ManagerUser, UUID> {

    Optional<ManagerUser> findByEmail(String email);

    boolean existsByEmail(String email);
}
