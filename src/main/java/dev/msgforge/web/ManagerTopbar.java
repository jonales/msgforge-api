// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.web;

import dev.msgforge.access.AccessService;
import dev.msgforge.access.ManagerPrincipal;
import dev.msgforge.access.Permission;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Quem está logado e o que aparece no menu lateral fora da instância ({@code ${@managerTopbar.view()}}
 * nos fragmentos {@code header} e {@code sideInstance}). Só roda quando a lateral é desenhada, e não nos
 * trechos do polling e do SSE.
 */
@Component("managerTopbar")
public class ManagerTopbar {

    private final AccessService access;

    public ManagerTopbar(AccessService access) {
        this.access = access;
    }

    /**
     * @param email   null quando entrou com a chave global
     * @param users   mostra "Usuários" (gerencia usuários em alguma organização)
     * @param roles   mostra "Papéis" (gerencia papéis em alguma organização)
     * @param server  mostra "Servidor" (admin da plataforma)
     * @param audit   mostra "Auditoria" (vê a auditoria em alguma organização)
     * @param current o item atual do menu ({@code instances}, {@code users}, {@code roles}, {@code audit},
     *                {@code server}) ou null
     */
    public record View(String email, boolean users, boolean roles, boolean server, boolean audit, String current) {}

    public View view() {
        var who = current();
        var page = section(path());
        if (who == null) {
            return new View(null, false, false, false, false, page);
        }
        return new View(who.email(), !access.organizationsWhere(who, Permission.USERS_MANAGE).isEmpty(),
                !access.organizationsWhere(who, Permission.ROLES_MANAGE).isEmpty(), access.isPlatformAdmin(who),
                !access.organizationsWhere(who, Permission.AUDIT_VIEW).isEmpty(), page);
    }

    /** O item do menu lateral de uma rota do Manager (o caminho sem o contexto); null se nenhum. */
    static String section(String path) {
        if (path == null) {
            return null;
        }
        var rest = path.startsWith("/manager") ? path.substring("/manager".length()) : path;
        if (rest.isEmpty() || rest.equals("/") || rest.startsWith("/instances")) {
            return "instances";
        }
        for (var section : new String[] {"users", "roles", "audit", "server"}) {
            if (rest.equals("/" + section) || rest.startsWith("/" + section + "/")) {
                return section;
            }
        }
        return null;
    }

    private static String path() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            var request = attributes.getRequest();
            return request.getRequestURI().substring(request.getContextPath().length());
        }
        return null;
    }

    /** O usuário da requisição; null fora de uma sessão do Manager. */
    static ManagerPrincipal current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof ManagerPrincipal who ? who : null;
    }
}
