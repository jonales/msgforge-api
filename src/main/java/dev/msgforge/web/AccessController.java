package dev.msgforge.web;

import dev.msgforge.access.AccessAdminService;
import dev.msgforge.access.AccessAdminService.AccessRuleException;
import dev.msgforge.access.AccessService;
import dev.msgforge.access.ManagerPrincipal;
import dev.msgforge.access.Organization;
import dev.msgforge.access.Permission;
import dev.msgforge.web.AccessViews.MemberRow;
import dev.msgforge.web.AccessViews.OrgOption;
import dev.msgforge.web.AccessViews.PermissionGroupView;
import dev.msgforge.web.AccessViews.RoleForm;
import dev.msgforge.web.AccessViews.RoleOption;
import dev.msgforge.web.AccessViews.RoleRow;
import dev.msgforge.web.AccessViews.UserForm;
import jakarta.servlet.http.HttpServletResponse;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Usuários e papéis da organização (ADR 0010). "Usuários" exige {@link Permission#USERS_MANAGE};
 * "Papéis", {@link Permission#ROLES_MANAGE}. Sem a permissão em nenhuma organização, 403. As
 * travas (sem escalada, último gestor de papéis, papel em uso) ficam no {@link AccessAdminService}.
 */
@Controller
@RequestMapping("/manager")
public class AccessController {

    private final AccessService access;
    private final AccessAdminService admin;

    public AccessController(AccessService access, AccessAdminService admin) {
        this.access = access;
        this.admin = admin;
    }

    // ------------------------------------------------------------ usuários

    @GetMapping("/users")
    public String users(@RequestParam(required = false) UUID org, Model model, HttpServletResponse response) {
        var who = ManagerTopbar.current();
        var organization = organizationFor(who, Permission.USERS_MANAGE, org);
        if (organization.isEmpty()) {
            return forbidden(response);
        }
        fillUsers(who, organization.get(), model);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", UserForm.EMPTY);
        }
        return "manager/users";
    }

    @PostMapping("/users")
    public String createUser(@RequestParam UUID org, @RequestParam String email, @RequestParam String password,
                             @RequestParam(required = false) UUID roleId,
                             @RequestParam(defaultValue = "false") boolean platformAdmin,
                             Model model, RedirectAttributes flash, HttpServletResponse response) {
        var who = ManagerTopbar.current();
        try {
            var user = admin.createUser(who, org, email, password, roleId, platformAdmin);
            flash.addFlashAttribute("createdEmail", user.getEmail());
            return "redirect:/manager/users?org=" + org;
        } catch (AccessRuleException e) {
            // Volta à tela com o que foi digitado (menos a senha) e o erro junto do formulário
            model.addAttribute("form", new UserForm(email, roleId, platformAdmin));
            model.addAttribute("formError", e.getMessage());
            return users(org, model, response);
        }
    }

    @PostMapping("/users/{userId}/role")
    public String changeRole(@PathVariable UUID userId, @RequestParam UUID org, @RequestParam UUID roleId,
                             RedirectAttributes flash) {
        return attempt(flash, "manager.users.roleChanged", "/manager/users?org=" + org,
                () -> admin.changeRole(ManagerTopbar.current(), org, userId, roleId));
    }

    @PostMapping("/users/{userId}/status")
    public String setStatus(@PathVariable UUID userId, @RequestParam UUID org, @RequestParam boolean blocked,
                            RedirectAttributes flash) {
        return attempt(flash, blocked ? "manager.users.blocked" : "manager.users.unblocked",
                "/manager/users?org=" + org, () -> admin.setBlocked(ManagerTopbar.current(), org, userId, blocked));
    }

    private void fillUsers(ManagerPrincipal who, Organization organization, Model model) {
        var orgId = organization.getId();
        var mine = access.permissionsIn(who, orgId);
        var isAdmin = isPlatformAdmin(who);
        model.addAttribute("organization", OrgOption.of(organization));
        model.addAttribute("organizations",
                access.organizationsWhere(who, Permission.USERS_MANAGE).stream().map(OrgOption::of).toList());
        model.addAttribute("members", admin.members(who, orgId).stream()
                .map(m -> MemberRow.of(m, mine, isAdmin, who.userId())).toList());
        model.addAttribute("roles", admin.roles(who, orgId).stream().map(r -> RoleOption.of(r, mine)).toList());
        model.addAttribute("platformAdmin", isAdmin);
        model.addAttribute("canManageRoles", mine.contains(Permission.ROLES_MANAGE));
        model.addAttribute("passwordMin", AccessAdminService.PASSWORD_MIN);
    }

    // ------------------------------------------------------------ papéis

    @GetMapping("/roles")
    public String roles(@RequestParam(required = false) UUID org, Model model, HttpServletResponse response) {
        var who = ManagerTopbar.current();
        var organization = organizationFor(who, Permission.ROLES_MANAGE, org);
        if (organization.isEmpty()) {
            return forbidden(response);
        }
        var orgId = organization.get().getId();
        var mine = access.permissionsIn(who, orgId);
        var usage = admin.roleUsage(orgId);
        model.addAttribute("organization", OrgOption.of(organization.get()));
        model.addAttribute("organizations",
                access.organizationsWhere(who, Permission.ROLES_MANAGE).stream().map(OrgOption::of).toList());
        model.addAttribute("roles", admin.roles(who, orgId).stream()
                .map(r -> RoleRow.of(r, usage.getOrDefault(r.getId(), 0L), mine)).toList());
        model.addAttribute("canManageUsers", mine.contains(Permission.USERS_MANAGE));
        return "manager/roles";
    }

    @GetMapping("/roles/new")
    public String newRole(@RequestParam UUID org, Model model, HttpServletResponse response) {
        var who = ManagerTopbar.current();
        var organization = organizationFor(who, Permission.ROLES_MANAGE, org);
        if (organization.isEmpty()) {
            return forbidden(response);
        }
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new RoleForm(null, "", false));
            model.addAttribute("checked", Set.of());
        }
        return roleForm(who, organization.get(), true, model);
    }

    @GetMapping("/roles/{roleId}")
    public String editRole(@PathVariable UUID roleId, @RequestParam UUID org, Model model,
                           HttpServletResponse response) {
        var who = ManagerTopbar.current();
        var organization = organizationFor(who, Permission.ROLES_MANAGE, org);
        if (organization.isEmpty()) {
            return forbidden(response);
        }
        var role = admin.role(who, org, roleId);
        var mine = access.permissionsIn(who, org);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form",
                    new RoleForm(role.getId(), role.getName(), admin.roleUsage(org).getOrDefault(roleId, 0L) > 0));
            model.addAttribute("checked", role.getPermissions());
        }
        return roleForm(who, organization.get(), mine.containsAll(role.getPermissions()), model);
    }

    @PostMapping("/roles")
    public String createRole(@RequestParam UUID org, @RequestParam String name,
                             @RequestParam(required = false) List<String> permissions,
                             Model model, RedirectAttributes flash, HttpServletResponse response) {
        var who = ManagerTopbar.current();
        Set<Permission> wanted = EnumSet.noneOf(Permission.class);
        try {
            wanted = AccessAdminService.parsePermissions(permissions);
            admin.createRole(who, org, name, wanted);
            flash.addFlashAttribute("success", "manager.roles.created");
            return "redirect:/manager/roles?org=" + org;
        } catch (AccessRuleException e) {
            model.addAttribute("form", new RoleForm(null, name, false));
            model.addAttribute("checked", wanted);
            model.addAttribute("formError", e.getMessage());
            return newRole(org, model, response);
        }
    }

    @PostMapping("/roles/{roleId}")
    public String updateRole(@PathVariable UUID roleId, @RequestParam UUID org, @RequestParam String name,
                             @RequestParam(required = false) List<String> permissions,
                             Model model, RedirectAttributes flash, HttpServletResponse response) {
        var who = ManagerTopbar.current();
        Set<Permission> wanted = EnumSet.noneOf(Permission.class);
        try {
            wanted = AccessAdminService.parsePermissions(permissions);
            admin.updateRole(who, org, roleId, name, wanted);
            flash.addFlashAttribute("success", "manager.roles.saved");
            return "redirect:/manager/roles?org=" + org;
        } catch (AccessRuleException e) {
            model.addAttribute("form", new RoleForm(roleId, name, admin.roleUsage(org).getOrDefault(roleId, 0L) > 0));
            model.addAttribute("checked", wanted);
            model.addAttribute("formError", e.getMessage());
            return editRole(roleId, org, model, response);
        }
    }

    @PostMapping("/roles/{roleId}/delete")
    public String deleteRole(@PathVariable UUID roleId, @RequestParam UUID org, RedirectAttributes flash) {
        return attempt(flash, "manager.roles.deleted", "/manager/roles?org=" + org,
                () -> admin.deleteRole(ManagerTopbar.current(), org, roleId));
    }

    private String roleForm(ManagerPrincipal who, Organization organization, boolean editable, Model model) {
        var mine = access.permissionsIn(who, organization.getId());
        @SuppressWarnings("unchecked")
        var checked = (Set<Permission>) model.getAttribute("checked");
        model.addAttribute("organization", OrgOption.of(organization));
        model.addAttribute("groups", PermissionGroupView.of(checked, mine));
        model.addAttribute("editable", editable);
        return "manager/role";
    }

    // ------------------------------------------------------------ apoio

    /** A organização pedida, se a pessoa tem a permissão nela; sem pedido, a primeira em que tem. */
    private Optional<Organization> organizationFor(ManagerPrincipal who, Permission permission, UUID requested) {
        var allowed = access.organizationsWhere(who, permission);
        if (requested == null) {
            return allowed.stream().findFirst();
        }
        return allowed.stream().filter(org -> org.getId().equals(requested)).findFirst();
    }

    private boolean isPlatformAdmin(ManagerPrincipal who) {
        return who != null && access.isPlatformAdmin(who);
    }

    private static String forbidden(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return "manager/forbidden";
    }

    private static String attempt(RedirectAttributes flash, String success, String back, Runnable action) {
        try {
            action.run();
            flash.addFlashAttribute("success", success);
        } catch (AccessRuleException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + back;
    }
}
