package dev.msgforge.access;

import dev.msgforge.access.AccessRules.Member;
import dev.msgforge.access.AccessRules.Subject;
import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    public static final int PASSWORD_MIN = 10;
    /** O BCrypt só usa os primeiros 72 bytes; acima disso, recusa em vez de truncar calado. */
    static final int PASSWORD_MAX_BYTES = 72;
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

    public AccessAdminService(AccessService access, ManagerUserRepository users, MembershipRepository memberships,
                              RoleRepository roles, OrganizationRepository organizations, PasswordEncoder passwords) {
        this.access = access;
        this.users = users;
        this.memberships = memberships;
        this.roles = roles;
        this.organizations = organizations;
        this.passwords = passwords;
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
        var subject = require(actor, Permission.USERS_MANAGE, organizationId);
        var normalized = ManagerUser.normalizeEmail(email);
        if (normalized.length() > 254 || !EMAIL.matcher(normalized).matches()) {
            throw new AccessRuleException("manager.access.error.email");
        }
        checkPassword(password);
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
        var user = users.save(new ManagerUser(normalized, passwords.encode(password), platformAdmin));
        memberships.save(new Membership(user, role));
        return user;
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
        membership.changeRole(role);
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
        return roles.save(new Role(organizationId, cleanName, permissions));
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
        role.rename(cleanName);
        role.replacePermissions(permissions);
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

    static void checkPassword(String password) {
        if (password == null || password.length() < PASSWORD_MIN
                || password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
            throw new AccessRuleException("manager.access.error.password");
        }
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
