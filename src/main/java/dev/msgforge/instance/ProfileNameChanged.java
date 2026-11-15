// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.instance;

/**
 * O nome do perfil da conta chegou ou mudou depois do login (numa conta comum ele vem pela
 * sincronização, não no login). Evento interno: grava o nome e atualiza o Manager, sem sair para
 * webhook nem RabbitMQ.
 */
public record ProfileNameChanged(String instanceName, String profileName) {}
