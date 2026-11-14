package dev.msgforge.access;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Regras puras do acesso (ADR 0010), sem banco: o {@link AccessService} carrega o usuário e os
 * vínculos e pergunta aqui. Toda decisão "pode X em Y" passa por {@link #can}.
 */
final class AccessRules {

    private AccessRules() {}

    /** O que um vínculo concede: permissões do papel, na organização, em todas ou algumas instâncias. */
    record Grant(UUID organizationId, Set<Permission> permissions, boolean allInstances, Set<UUID> instanceIds) {

        Grant {
            permissions = permissions.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions);
            instanceIds = Set.copyOf(instanceIds);
        }

        boolean covers(UUID instanceId) {
            return allInstances || instanceIds.contains(instanceId);
        }
    }

    /**
     * Quem pede. {@code platformAdmin}: chave global ou usuário administrador da plataforma (tudo,
     * em todas as organizações). Um usuário que não está ativo não pode nada.
     */
    record Subject(boolean platformAdmin, boolean active, List<Grant> grants) {

        static final Subject NOBODY = new Subject(false, false, List.of());

        static Subject admin() {
            return new Subject(true, true, List.of());
        }

        Subject {
            grants = List.copyOf(grants);
        }
    }

    /**
     * Pode {@code permission} na organização {@code organizationId}? Com {@code instanceId}, só se o
     * vínculo também cobre essa instância (escopo por instância); sem ela, vale a permissão na
     * organização (como gerenciar usuários e papéis).
     */
    static boolean can(Subject who, Permission permission, UUID organizationId, UUID instanceId) {
        if (!who.active()) {
            return false;
        }
        if (who.platformAdmin()) {
            return true;
        }
        return who.grants().stream().anyMatch(grant -> grant.organizationId().equals(organizationId)
                && grant.permissions().contains(permission)
                && (instanceId == null || grant.covers(instanceId)));
    }

    /** Tem a permissão em alguma organização (em alguma instância dela ou na organização toda)? */
    static boolean canSomewhere(Subject who, Permission permission) {
        if (!who.active()) {
            return false;
        }
        return who.platformAdmin() || who.grants().stream().anyMatch(g -> g.permissions().contains(permission));
    }

    /** Permissões na organização como um todo (o que a pessoa pode conceder a outras). */
    static Set<Permission> permissionsIn(Subject who, UUID organizationId) {
        if (!who.active()) {
            return EnumSet.noneOf(Permission.class);
        }
        if (who.platformAdmin()) {
            return Permission.all();
        }
        var result = EnumSet.noneOf(Permission.class);
        who.grants().stream().filter(grant -> grant.organizationId().equals(organizationId))
                .forEach(grant -> result.addAll(grant.permissions()));
        return result;
    }

    /** Sem escalada: só concede (ou mexe em papel com) permissões que a pessoa já tem. */
    static boolean canGrant(Subject who, UUID organizationId, Set<Permission> wanted) {
        return permissionsIn(who, organizationId).containsAll(wanted);
    }

    /** Um membro da organização, para a trava "pelo menos uma pessoa com gerenciar papéis". */
    record Member(UUID userId, boolean active, Set<Permission> permissions) {

        boolean managesRoles() {
            return active && permissions.contains(Permission.ROLES_MANAGE);
        }
    }

    /**
     * A organização mantém pelo menos uma pessoa ativa com "gerenciar papéis" depois da mudança. Se
     * ela já não tinha nenhuma (organização nova, ou só o admin da plataforma cuida dela), a trava
     * não se aplica: não há o que preservar.
     */
    static boolean keepsARoleManager(List<Member> before, List<Member> after) {
        return before.stream().noneMatch(Member::managesRoles) || after.stream().anyMatch(Member::managesRoles);
    }
}
