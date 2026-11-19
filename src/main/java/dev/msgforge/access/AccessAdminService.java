// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.access;

import dev.msgforge.access.AccessRules.Member;
import dev.msgforge.access.AccessRules.Subject;
import dev.msgforge.audit.AuditAction;
import dev.msgforge.audit.AuditEvent;
import dev.msgforge.audit.AuditLog;
import dev.msgforge.audit.AuditOutcome;
import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Usuários, papéis e organizações (ADR 0010), com as travas: ninguém concede permissão que não tem,
 * a organização mantém pelo menos uma pessoa com "gerenciar papéis" e papel em uso não é apagado.
 * Cada método confere a permissão de quem pede; a tela confere antes, mas a regra vale aqui.
 */
@Service
public class AccessAdminService {

    public static final int PASSWORD_MIN = PasswordPolicy.MIN;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** Erro de regra para a tela: {@code getMessage()} é a chave em {@code messages.properties}. */
    public static class AccessRuleException extends BadRequestException {
        public AccessRuleException(String messageKey) {
            super(messageKey);
        }
    }

    private final AccessService access;
    private final ManagerUserRepository users;
    private final MembershipRepository memberships;
    private final RoleRepository roles;
    private final OrganizationRepository organizations;
    private final PasswordEncoder passwords;
    private final AuditLog audit;

    public AccessAdminService(AccessService access, ManagerUserRepository users, MembershipRepository memberships,
                              RoleRepository roles, OrganizationRepository organizations, PasswordEncoder passwords,
                              AuditLog audit) {
        this.access = access;
        this.users = users;
        this.memberships = memberships;
        this.roles = roles;
        this.organizations = organizations;
        this.passwords = passwords;
        this.audit = audit;
    }

    // ------------------------------------------------------------ leitura

    @Transactional(readOnly = true)
    public Organization organization(UUID id) {
        return organizations.findById(id).orElseThrow(() -> new AccessRuleException("manager.access.error.orgNotFound"));
    }

    @Transactional(readOnly = true)
    public List<Membership> members(ManagerPrincipal actor, UUID organizationId) {
        require(actor, Permission.USERS_MANAGE, organizationId);
        return memberships.findByOrganizationIdOrderByUserEmailAsc(organizationId);
    }

    /** Papéis da organização, para quem gerencia usuários (escolher o papel) ou papéis. */
    @Transactional(readOnly = true)
    public List<Role> roles(ManagerPrincipal actor, UUID organizationId) {
        var subject = access.subject(actor);
        if (!AccessRules.can(subject, Permission.USERS_MANAGE, organizationId, null)
                && !AccessRules.can(subject, Permission.ROLES_MANAGE, organizationId, null)) {
            throw new AccessDeniedException("sem permissão");
        }
        return roles.findByOrganizationIdOrderByNameAsc(organizationId);
    }

    @Transactional(readOnly = true)
    public Role role(ManagerPrincipal actor, UUID organizationId, UUID roleId) {
        require(actor, Permission.ROLES_MANAGE, organizationId);
        return roleOf(organizationId, roleId);
    }

    /** Quantas pessoas usam cada papel da organização. */
    @Transactional(readOnly = true)
    public Map<UUID, Long> roleUsage(UUID organizationId) {
        return memberships.findByOrganizationIdOrderByUserEmailAsc(organizationId).stream()
                .collect(Collectors.groupingBy(m -> m.getRole().getId(), Collectors.counting()));
    }

    // ------------------------------------------------------------ usuários

    /**
     * Cria um usuário ativo, com senha inicial, vinculado à organização com um papel. Só o admin da
     * plataforma cria outro admin da plataforma. Convite por e-mail: fatia futura (ADR 0011).
     */
    @Transactional
    public ManagerUser createUser(ManagerPrincipal actor, UUID organizationId, String email, String password,
                                  UUID roleId, boolean platformAdmin) {
        return createUser(actor, organizationId, email, null, password, roleId, platformAdmin);
    }

    /** Como o outro, com o nome de exibição (opcional: sem ele, a pessoa responde sem assinatura). */
    @Transactional
    public ManagerUser createUser(ManagerPrincipal actor, UUID organizationId, String email, String displayName,
                                  String password, UUID roleId, boolean platformAdmin) {
        var subject = require(actor, Permission.USERS_MANAGE, organizationId);
        var name = displayName(displayName);
        var normalized = ManagerUser.normalizeEmail(email);
        if (normalized.length() > 254 || !EMAIL.matcher(normalized).matches()) {
            throw new AccessRuleException("manager.access.error.email");
        }
        checkPassword(password, email, displayName);
        var role = roleOf(organizationId, roleId);
        if (!AccessRules.canGrant(subject, organizationId, role.getPermissions())) {
            throw new AccessRuleException("manager.access.error.escalation");
        }
        if (platformAdmin && !subject.platformAdmin()) {
            throw new AccessRuleException("manager.access.error.adminOnly");
        }
        if (users.existsByEmail(normalized)) {
            throw new AccessRuleException("manager.access.error.emailTaken");
        }
        var created = new ManagerUser(normalized, passwords.encode(password), platformAdmin);
        created.setDisplayName(name);
        var user = users.save(created);
        memberships.save(new Membership(user, role));
        audit(actor, AuditAction.USER_CREATED, organizationId, user.getEmail(),
                platformAdmin ? role.getName() + " + admin da plataforma" : role.getName());
        return user;
    }

    /**
     * Troca o nome de exibição. A própria pessoa sempre pode; outra, com "gerenciar usuários" e sem
     * escalada (as mesmas travas do bloqueio). Vazio tira o nome (responde sem assinatura).
     */
    @Transactional
    public void setDisplayName(ManagerPrincipal actor, UUID organizationId, UUID userId, String displayName) {
        var name = displayName(displayName);
        if (!userId.equals(actor.userId())) {
            var subject = require(actor, Permission.USERS_MANAGE, organizationId);
            var membership = membershipOf(organizationId, userId);
            if ((membership.getUser().isPlatformAdmin() && !subject.platformAdmin())
                    || !AccessRules.canGrant(subject, organizationId, membership.getRole().getPermissions())) {
                throw new AccessRuleException("manager.access.error.escalation");
            }
        }
        var user = users.findById(userId)
                .orElseThrow(() -> new AccessRuleException("manager.access.error.userNotFound"));
        user.setDisplayName(name);
        audit(actor, AuditAction.USER_RENAMED, organizationId, user.getEmail(), null);
    }

    /** O nome de exibição da pessoa, lido na hora (null sem nome ou para a chave global). */
    @Transactional(readOnly = true)
    public String displayNameOf(ManagerPrincipal who) {
        return who == null || who.globalKey() ? null
                : users.findById(who.userId()).map(ManagerUser::getDisplayName).orElse(null);
    }

    /** Limpo; recusa o que passa do tamanho (vazio é aceito e quer dizer "sem nome"). */
    private static String displayName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        var clean = ManagerUser.normalizeDisplayName(raw);
        if (clean == null && raw.strip().length() > ManagerUser.DISPLAY_NAME_MAX) {
            throw new AccessRuleException("manager.access.error.displayName");
        }
        return clean;
    }

    @Transactional
    public void changeRole(ManagerPrincipal actor, UUID organizationId, UUID userId, UUID roleId) {
        var subject = require(actor, Permission.USERS_MANAGE, organizationId);
        var membership = membershipOf(organizationId, userId);
        var role = roleOf(organizationId, roleId);
        // Sem escalada nos dois sentidos: não dá papel acima do seu, nem mexe em quem está acima
        if (!AccessRules.canGrant(subject, organizationId, role.getPermissions())
                || !AccessRules.canGrant(subject, organizationId, membership.getRole().getPermissions())) {
            throw new AccessRuleException("manager.access.error.escalation");
        }
        checkKeepsRoleManager(organizationId, m -> m.getId().equals(membership.getId())
                ? new Member(m.getUser().getId(), m.getUser().isActive(), role.getPermissions()) : member(m));
        var before = membership.getRole().getName();
        membership.changeRole(role);
        audit(actor, AuditAction.USER_ROLE_CHANGED, organizationId, membership.getUser().getEmail(),
                before + " → " + role.getName());
    }

    /** Bloqueia ou reativa. O bloqueio vale para o usuário inteiro (todas as organizações). */
    @Transactional
    public void setBlocked(ManagerPrincipal actor, UUID organizationId, UUID userId, boolean blocked) {
        var subject = require(actor, Permission.USERS_MANAGE, organizationId);
        var membership = membershipOf(organizationId, userId);
        var user = membership.getUser();
        if (userId.equals(actor.userId())) {
            throw new AccessRuleException("manager.access.error.self");
        }
        if ((user.isPlatformAdmin() && !subject.platformAdmin())
                || !AccessRules.canGrant(subject, organizationId, membership.getRole().getPermissions())) {
            throw new AccessRuleException("manager.access.error.escalation");
        }
        if (blocked) {
            checkKeepsRoleManager(organizationId, m -> m.getUser().getId().equals(userId)
                    ? new Member(userId, false, m.getRole().getPermissions()) : member(m));
        }
        user.setStatus(blocked ? UserStatus.BLOQUEADO : UserStatus.ATIVO);
        audit(actor, blocked ? AuditAction.USER_BLOCKED : AuditAction.USER_UNBLOCKED, organizationId,
                user.getEmail(), null);
    }

    // ------------------------------------------------------------ papéis

    @Transactional
    public Role createRole(ManagerPrincipal actor, UUID organizationId, String name, Set<Permission> permissions) {
        var subject = require(actor, Permission.ROLES_MANAGE, organizationId);
        var cleanName = roleName(name);
        if (!AccessRules.canGrant(subject, organizationId, permissions)) {
            throw new AccessRuleException("manager.access.error.escalation");
        }
        if (roles.existsByOrganizationIdAndNameIgnoreCase(organizationId, cleanName)) {
            throw new AccessRuleException("manager.access.error.roleTaken");
        }
        var role = roles.save(new Role(organizationId, cleanName, permissions));
        audit(actor, AuditAction.ROLE_CREATED, organizationId, cleanName, permissionDiff(Set.of(), permissions));
        return role;
    }

    @Transactional
    public void updateRole(ManagerPrincipal actor, UUID organizationId, UUID roleId, String name,
                           Set<Permission> permissions) {
        var subject = require(actor, Permission.ROLES_MANAGE, organizationId);
        var role = roleOf(organizationId, roleId);
        var cleanName = roleName(name);
        if (!AccessRules.canGrant(subject, organizationId, role.getPermissions())
                || !AccessRules.canGrant(subject, organizationId, permissions)) {
            throw new AccessRuleException("manager.access.error.escalation");
        }
        if (!cleanName.equalsIgnoreCase(role.getName())
                && roles.existsByOrganizationIdAndNameIgnoreCase(organizationId, cleanName)) {
            throw new AccessRuleException("manager.access.error.roleTaken");
        }
        checkKeepsRoleManager(organizationId, m -> m.getRole().getId().equals(roleId)
                ? new Member(m.getUser().getId(), m.getUser().isActive(), permissions) : member(m));
        var target = cleanName.equals(role.getName()) ? cleanName : role.getName() + " → " + cleanName;
        var diff = permissionDiff(role.getPermissions(), permissions);
        role.rename(cleanName);
        role.replacePermissions(permissions);
        audit(actor, AuditAction.ROLE_UPDATED, organizationId, target, diff);
    }

    @Transactional
    public void deleteRole(ManagerPrincipal actor, UUID organizationId, UUID roleId) {
        var subject = require(actor, Permission.ROLES_MANAGE, organizationId);
        var role = roleOf(organizationId, roleId);
        if (!AccessRules.canGrant(subject, organizationId, role.getPermissions())) {
            throw new AccessRuleException("manager.access.error.escalation");
        }
        if (memberships.countByRoleId(roleId) > 0) {
            throw new AccessRuleException("manager.access.error.roleInUse");
        }
        roles.delete(role);
        audit(actor, AuditAction.ROLE_DELETED, organizationId, role.getName(), null);
    }

    // ------------------------------------------------------------ auditoria (ADR 0018)

    /** Grava depois do commit: uma ação recusada pelas travas acima nunca chega aqui. */
    private void audit(ManagerPrincipal actor, AuditAction action, UUID organizationId, String target, String detail) {
        audit.record(new AuditEvent(action, AuditOutcome.OK, organizationId, actor.userId(),
                actor.globalKey() ? AuditLog.GLOBAL_KEY : actor.email(), target, detail, null));
    }

    /**
     * O que entrou e saiu do papel, como {@code +CHAT_SEND -USERS_MANAGE} (nomes do catálogo, na
     * ordem dele; a tela traduz). Null sem mudança.
     */
    static String permissionDiff(Set<Permission> before, Set<Permission> after) {
        var parts = Arrays.stream(Permission.values())
                .filter(p -> before.contains(p) != after.contains(p))
                .map(p -> (after.contains(p) ? "+" : "-") + p.name())
                .toList();
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    // ------------------------------------------------------------ organizações

    /** Organização nova, com os papéis-modelo. Só o admin da plataforma (sem tela nesta fatia). */
    @Transactional
    public Organization createOrganization(ManagerPrincipal actor, String name) {
        if (!access.subject(actor).platformAdmin()) {
            throw new AccessDeniedException("só o admin da plataforma");
        }
        var clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > 100) {
            throw new AccessRuleException("manager.access.error.orgName");
        }
        if (organizations.existsByNameIgnoreCase(clean)) {
            throw new AccessRuleException("manager.access.error.orgTaken");
        }
        var organization = organizations.save(new Organization(clean));
        for (var template : RoleTemplate.values()) {
            roles.save(new Role(organization.getId(), template.roleName(), template.permissions()));
        }
        return organization;
    }

    // ------------------------------------------------------------ apoio

    private Subject require(ManagerPrincipal actor, Permission permission, UUID organizationId) {
        var subject = access.subject(actor);
        if (!AccessRules.can(subject, permission, organizationId, null)) {
            throw new AccessDeniedException("sem permissão: " + permission);
        }
        return subject;
    }

    private Role roleOf(UUID organizationId, UUID roleId) {
        return roles.findById(roleId == null ? new UUID(0, 0) : roleId)
                .filter(role -> role.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new AccessRuleException("manager.access.error.roleNotFound"));
    }

    private Membership membershipOf(UUID organizationId, UUID userId) {
        return memberships.findByUserIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> new AccessRuleException("manager.access.error.userNotFound"));
    }

    private static Member member(Membership m) {
        return new Member(m.getUser().getId(), m.getUser().isActive(), m.getRole().getPermissions());
    }

    private void checkKeepsRoleManager(UUID organizationId, Function<Membership, Member> after) {
        var current = memberships.findByOrganizationIdOrderByUserEmailAsc(organizationId);
        var before = current.stream().map(AccessAdminService::member).toList();
        var changed = new ArrayList<Member>();
        current.forEach(m -> changed.add(after.apply(m)));
        if (!AccessRules.keepsARoleManager(before, changed)) {
            throw new AccessRuleException("manager.access.error.lastRoleManager");
        }
    }

    private static String roleName(String name) {
        var clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > 60) {
            throw new AccessRuleException("manager.access.error.roleName");
        }
        return clean;
    }

    /** A senha cumpre a {@link PasswordPolicy}? Senão, o erro diz o motivo (chave por problema). */
    static void checkPassword(String password, String... context) {
        PasswordPolicy.check(password, context).ifPresent(problem -> {
            throw new AccessRuleException("manager.access.error.password." + problem.name().toLowerCase(java.util.Locale.ROOT));
        });
    }

    /** Permissões vindas do formulário (nomes do catálogo); nome desconhecido é recusado. */
    public static Set<Permission> parsePermissions(List<String> names) {
        try {
            return Permission.parse(names);
        } catch (IllegalArgumentException e) {
            throw new AccessRuleException("manager.access.error.permission");
        }
    }
}
