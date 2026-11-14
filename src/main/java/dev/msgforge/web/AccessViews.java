package dev.msgforge.web;

import dev.msgforge.access.Membership;
import dev.msgforge.access.Organization;
import dev.msgforge.access.Permission;
import dev.msgforge.access.Role;
import dev.msgforge.access.UserStatus;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Modelos das telas de usuários e papéis (ADR 0010). Públicos: o SpEL do Thymeleaf exige. */
public final class AccessViews {

    private AccessViews() {}

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    public record OrgOption(UUID id, String name) {
        static OrgOption of(Organization org) {
            return new OrgOption(org.getId(), org.getName());
        }
    }

    /**
     * @param manageable quem vê a tela pode trocar o papel e bloquear este usuário (sem escalada)
     * @param self       é a própria pessoa (não se bloqueia)
     */
    public record MemberRow(UUID userId, String email, UUID roleId, String roleName, String status,
                            boolean platformAdmin, String lastLogin, boolean manageable, boolean self) {

        static MemberRow of(Membership m, Set<Permission> actorPermissions, boolean actorIsAdmin, UUID actorId) {
            var user = m.getUser();
            var manageable = actorPermissions.containsAll(m.getRole().getPermissions())
                    && (actorIsAdmin || !user.isPlatformAdmin());
            return new MemberRow(user.getId(), user.getEmail(), m.getRole().getId(), m.getRole().getName(),
                    user.getStatus().name(), user.isPlatformAdmin(),
                    user.getLastLoginAt() == null ? null : WHEN.format(user.getLastLoginAt()),
                    manageable, user.getId().equals(actorId));
        }

        public boolean blocked() {
            return UserStatus.BLOQUEADO.name().equals(status);
        }
    }

    /** {@code grantable}: quem vê a tela tem todas as permissões do papel e pode atribuí-lo. */
    public record RoleOption(UUID id, String name, boolean grantable) {
        static RoleOption of(Role role, Set<Permission> actorPermissions) {
            return new RoleOption(role.getId(), role.getName(), actorPermissions.containsAll(role.getPermissions()));
        }
    }

    /** @param permissions nomes do catálogo, na ordem do catálogo */
    public record RoleRow(UUID id, String name, List<String> permissions, long users, boolean editable) {
        static RoleRow of(Role role, long users, Set<Permission> actorPermissions) {
            var names = Arrays.stream(Permission.values()).filter(role.getPermissions()::contains)
                    .map(Enum::name).toList();
            return new RoleRow(role.getId(), role.getName(), names, users,
                    actorPermissions.containsAll(role.getPermissions()));
        }
    }

    /**
     * Permissões do catálogo cuja tela ainda não existe: aparecem com "Em breve" (PRODUCT.md,
     * "honesto sobre o que existe"). Tire daqui quando a função chegar.
     */
    static final Set<Permission> SOON = Set.of(Permission.INSTANCE_CONNECT_LINK, Permission.AUDIT_VIEW);

    /** @param soon a função ainda não existe; dá para marcar, para o papel já nascer pronto */
    public record PermissionOption(String name, boolean checked, boolean grantable, boolean soon) {}

    public record PermissionGroupView(String name, List<PermissionOption> permissions) {

        /** O catálogo inteiro, por grupo, marcando o que está no papel e o que a pessoa pode conceder. */
        static List<PermissionGroupView> of(Set<Permission> checked, Set<Permission> actorPermissions) {
            return Arrays.stream(Permission.Group.values())
                    .map(group -> new PermissionGroupView(group.name(), group.permissions().stream()
                            .map(p -> new PermissionOption(p.name(), checked.contains(p),
                                    actorPermissions.contains(p), SOON.contains(p)))
                            .toList()))
                    .toList();
        }
    }

    /** O que a pessoa digitou no "novo usuário", para voltar à tela com o erro (nunca a senha). */
    public record UserForm(String email, UUID roleId, boolean platformAdmin) {
        static final UserForm EMPTY = new UserForm("", null, false);
    }

    /** @param id null para papel novo */
    public record RoleForm(UUID id, String name, boolean inUse) {}
}
