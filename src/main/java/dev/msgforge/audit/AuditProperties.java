// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Auditoria do Manager (ADR 0018).
 *
 * @param retentionDays apaga registros mais velhos que isso (AUDIT_RETENTION_DAYS, padrão 180: o IP é
 *                      dado pessoal); 0 guarda para sempre
 */
@ConfigurationProperties(prefix = "msgforge.audit")
public record AuditProperties(@DefaultValue("180") int retentionDays) {}
