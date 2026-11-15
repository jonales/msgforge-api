// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.access;

/** Estado do usuário do Manager (ADR 0010). Só {@link #ATIVO} entra. */
public enum UserStatus {
    /** Convite enviado, senha ainda não definida (o convite por e-mail é de uma fatia futura). */
    CONVIDADO,
    ATIVO,
    BLOQUEADO
}
