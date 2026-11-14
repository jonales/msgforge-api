package dev.msgforge.access;

import static dev.msgforge.access.Permission.CHAT_SEND;
import static dev.msgforge.access.Permission.CHAT_VIEW;
import static dev.msgforge.access.Permission.INSTANCE_CONNECT;
import static dev.msgforge.access.Permission.INSTANCE_CONNECT_LINK;
import static dev.msgforge.access.Permission.INSTANCE_VIEW;

import java.util.EnumSet;
import java.util.Set;

/**
 * Papéis-modelo (ADR 0010): toda organização nova começa com estes e pode editá-los, apagá-los ou
 * criar outros. A organização padrão os recebe na migration
 * {@code V202611141328__cria_organizacoes_usuarios_e_papeis_do_manager}; o {@code AccessModelIT}
 * confere que os dois continuam iguais.
 */
public enum RoleTemplate {

    GESTOR("Gestor", Permission.all()),
    OPERADOR("Operador", EnumSet.of(INSTANCE_VIEW, INSTANCE_CONNECT, INSTANCE_CONNECT_LINK, CHAT_VIEW, CHAT_SEND)),
    ATENDENTE("Atendente", EnumSet.of(INSTANCE_VIEW, CHAT_VIEW, CHAT_SEND)),
    /** O cliente do gestor: vê o status e conecta o próprio número (QR). */
    CLIENTE("Cliente", EnumSet.of(INSTANCE_VIEW, INSTANCE_CONNECT));

    private final String roleName;
    private final Set<Permission> permissions;

    RoleTemplate(String roleName, Set<Permission> permissions) {
        this.roleName = roleName;
        this.permissions = Set.copyOf(permissions);
    }

    public String roleName() {
        return roleName;
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}
