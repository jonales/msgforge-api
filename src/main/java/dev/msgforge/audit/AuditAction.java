// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.audit;

/** O que aconteceu (ADR 0018). Um valor novo exige migration no CHECK de {@code audit_event} e o texto {@code manager.audit.action.*}. */
public enum AuditAction {
    LOGIN, LOGOUT,
    USER_CREATED, USER_ROLE_CHANGED, USER_BLOCKED, USER_UNBLOCKED, USER_RENAMED,
    ROLE_CREATED, ROLE_UPDATED, ROLE_DELETED
}
