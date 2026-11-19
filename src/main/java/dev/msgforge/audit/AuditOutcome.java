// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.audit;

/**
 * Como terminou. {@code BLOCKED}: o IP passou do limite de tentativas; {@code DISABLED}: senha certa,
 * mas o usuário está bloqueado. As ações de pessoas só são gravadas quando dão certo ({@code OK}).
 */
public enum AuditOutcome {
    OK, FAILED, BLOCKED, DISABLED
}
