package dev.msgforge.web;

import dev.msgforge.access.AccessService;
import dev.msgforge.access.ManagerPrincipal;
import dev.msgforge.access.Permission;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Quem está logado, para o topo das telas ({@code ${@managerTopbar.view()}} no fragmento
 * {@code header}). Só roda quando o topo é desenhado, e não nos trechos do polling e do SSE.
 */
@Component("managerTopbar")
public class ManagerTopbar {

    private final AccessService access;

    public ManagerTopbar(AccessService access) {
        this.access = access;
    }

    /**
     * @param email null quando entrou com a chave global
     * @param users mostra o link "Usuários" (gerencia usuários em alguma organização)
     */
    public record View(String email, boolean users) {}

    public View view() {
        var who = current();
        if (who == null) {
            return new View(null, false);
        }
        return new View(who.email(), !access.organizationsWhere(who, Permission.USERS_MANAGE).isEmpty());
    }

    /** O usuário da requisição; null fora de uma sessão do Manager. */
    static ManagerPrincipal current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof ManagerPrincipal who ? who : null;
    }
}
