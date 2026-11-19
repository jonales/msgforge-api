// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.audit;

import java.time.Instant;
import java.util.UUID;

/** Um registro lido da tabela, para a tela. */
public record AuditEntry(long id, Instant at, AuditAction action, AuditOutcome outcome, UUID organizationId,
                         UUID actorUserId, String actorLabel, String target, String detail, String ip) {}
