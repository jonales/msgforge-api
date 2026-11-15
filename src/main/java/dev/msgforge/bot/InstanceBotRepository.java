// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.bot;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstanceBotRepository extends JpaRepository<InstanceBot, UUID> {}
