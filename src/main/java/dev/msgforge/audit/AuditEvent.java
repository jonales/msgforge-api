// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.audit;

import java.util.UUID;

/**
 * Um registro de auditoria, como vai para a tabela.
 *
 * @param organizationId a organização onde a ação valeu; null no login e no logout (valem para a pessoa)
 * @param actorUserId    quem agiu (no login com falha, a conta tentada, se existir); null para a chave global
 * @param actorLabel     o e-mail como era na hora, {@link AuditLog#GLOBAL_KEY} ou {@link AuditLog#UNKNOWN_ACCOUNT}
 * @param target         sobre quem ou o quê (e-mail do usuário, nome do papel); null no login
 * @param detail         complemento curto (ex.: papel antigo e novo); nunca senha nem o que foi digitado
 */
public record AuditEvent(AuditAction action, AuditOutcome outcome, UUID organizationId, UUID actorUserId,
                         String actorLabel, String target, String detail, String ip) {}
