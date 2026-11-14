package dev.msgforge.access;

import static dev.msgforge.access.Permission.CHAT_VIEW;
import static dev.msgforge.access.Permission.INSTANCE_CONNECT;
import static dev.msgforge.access.Permission.INSTANCE_DELETE;
import static dev.msgforge.access.Permission.INSTANCE_VIEW;
import static dev.msgforge.access.Permission.ROLES_MANAGE;
import static dev.msgforge.access.Permission.USERS_MANAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.msgforge.access.AccessRules.Grant;
import dev.msgforge.access.AccessRules.Member;
import dev.msgforge.access.AccessRules.Subject;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** "Pode X em Y" (ADR 0010), sem banco. */
class AccessRulesTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();
    private static final UUID SALES = UUID.randomUUID();
    private static final UUID SUPPORT = UUID.randomUUID();

    private static Subject member(Grant... grants) {
        return new Subject(false, true, List.of(grants));
    }

    private static Grant wholeOrg(UUID org, Permission... permissions) {
        return new Grant(org, EnumSet.copyOf(List.of(permissions)), true, Set.of());
    }

    @Test
    void theGlobalKeyAndPlatformAdminsCanEverythingEverywhere() {
        var admin = Subject.admin();
        for (var permission : Permission.values()) {
            assertThat(AccessRules.can(admin, permission, ORG, SALES)).isTrue();
            assertThat(AccessRules.can(admin, permission, OTHER_ORG, null)).isTrue();
        }
        assertThat(AccessRules.permissionsIn(admin, ORG)).isEqualTo(Permission.all());
    }

    @Test
    void aMemberCanOnlyWhatTheRoleGivesAndOnlyInItsOrganization() {
        var client = member(wholeOrg(ORG, INSTANCE_VIEW, INSTANCE_CONNECT));

        assertThat(AccessRules.can(client, INSTANCE_CONNECT, ORG, SALES)).isTrue();
        assertThat(AccessRules.can(client, INSTANCE_VIEW, ORG, null)).isTrue();
        assertThat(AccessRules.can(client, INSTANCE_DELETE, ORG, SALES)).isFalse();
        assertThat(AccessRules.can(client, CHAT_VIEW, ORG, SALES)).isFalse();
        // Mesma permissão, outra organização
        assertThat(AccessRules.can(client, INSTANCE_CONNECT, OTHER_ORG, SALES)).isFalse();
    }

    @Test
    void somewhereAsksForAnyOrganizationAndNobodyBlockedHasIt() {
        var boss = member(wholeOrg(OTHER_ORG, Permission.INSTANCE_CREATE));
        assertThat(AccessRules.canSomewhere(boss, Permission.INSTANCE_CREATE)).isTrue();
        assertThat(AccessRules.canSomewhere(boss, INSTANCE_DELETE)).isFalse();
        assertThat(AccessRules.canSomewhere(Subject.admin(), INSTANCE_DELETE)).isTrue();
        var blocked = new Subject(false, false, List.of(wholeOrg(ORG, Permission.INSTANCE_CREATE)));
        assertThat(AccessRules.canSomewhere(blocked, Permission.INSTANCE_CREATE)).isFalse();
        assertThat(AccessRules.canSomewhere(Subject.NOBODY, INSTANCE_VIEW)).isFalse();
    }

    @Test
    void anInstanceScopeLimitsTheGrantToTheChosenInstances() {
        var salesOnly = member(new Grant(ORG, EnumSet.of(INSTANCE_VIEW, CHAT_VIEW), false, Set.of(SALES)));

        assertThat(AccessRules.can(salesOnly, CHAT_VIEW, ORG, SALES)).isTrue();
        assertThat(AccessRules.can(salesOnly, CHAT_VIEW, ORG, SUPPORT)).isFalse();
        // A permissão na organização (sem instância) continua valendo
        assertThat(AccessRules.can(salesOnly, INSTANCE_VIEW, ORG, null)).isTrue();
    }

    @Test
    void grantsFromSeveralOrganizationsDoNotMix() {
        var who = member(wholeOrg(ORG, USERS_MANAGE), wholeOrg(OTHER_ORG, INSTANCE_VIEW));

        assertThat(AccessRules.permissionsIn(who, ORG)).containsExactly(USERS_MANAGE);
        assertThat(AccessRules.permissionsIn(who, OTHER_ORG)).containsExactly(INSTANCE_VIEW);
        assertThat(AccessRules.can(who, USERS_MANAGE, OTHER_ORG, null)).isFalse();
    }

    @Test
    void anInactiveUserCanNothingEvenAsAdmin() {
        var blockedAdmin = new Subject(true, false, List.of(wholeOrg(ORG, Permission.values())));

        assertThat(AccessRules.can(blockedAdmin, INSTANCE_VIEW, ORG, SALES)).isFalse();
        assertThat(AccessRules.permissionsIn(blockedAdmin, ORG)).isEmpty();
        assertThat(AccessRules.can(Subject.NOBODY, INSTANCE_VIEW, ORG, null)).isFalse();
    }

    @Test
    void nobodyGrantsAPermissionTheyDoNotHave() {
        var supervisor = member(wholeOrg(ORG, USERS_MANAGE, INSTANCE_VIEW));

        assertThat(AccessRules.canGrant(supervisor, ORG, EnumSet.of(INSTANCE_VIEW))).isTrue();
        assertThat(AccessRules.canGrant(supervisor, ORG, RoleTemplate.CLIENTE.permissions())).isFalse();
        assertThat(AccessRules.canGrant(supervisor, ORG, RoleTemplate.GESTOR.permissions())).isFalse();
        assertThat(AccessRules.canGrant(supervisor, OTHER_ORG, EnumSet.of(INSTANCE_VIEW))).isFalse();
        assertThat(AccessRules.canGrant(Subject.admin(), ORG, Permission.all())).isTrue();
    }

    @Test
    void theOrganizationKeepsSomeoneWhoManagesRoles() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var before = List.of(new Member(a, true, EnumSet.of(ROLES_MANAGE)), new Member(b, true, EnumSet.of(INSTANCE_VIEW)));

        // Tirar o único que gerencia papéis: recusado
        assertThat(AccessRules.keepsARoleManager(before,
                List.of(new Member(a, true, EnumSet.of(INSTANCE_VIEW)), before.get(1)))).isFalse();
        // Bloqueá-lo também conta como tirar
        assertThat(AccessRules.keepsARoleManager(before,
                List.of(new Member(a, false, EnumSet.of(ROLES_MANAGE)), before.get(1)))).isFalse();
        // Passar para outra pessoa antes: aceito
        assertThat(AccessRules.keepsARoleManager(before,
                List.of(new Member(a, true, EnumSet.of(INSTANCE_VIEW)), new Member(b, true, EnumSet.of(ROLES_MANAGE)))))
                .isTrue();
        // Organização que já não tinha ninguém: nada a preservar
        var nobody = List.of(new Member(b, true, EnumSet.of(INSTANCE_VIEW)));
        assertThat(AccessRules.keepsARoleManager(nobody, nobody)).isTrue();
    }

    @Test
    void templatesFollowTheAdr() {
        assertThat(RoleTemplate.GESTOR.permissions()).isEqualTo(Permission.all());
        assertThat(RoleTemplate.CLIENTE.permissions()).containsExactlyInAnyOrder(INSTANCE_VIEW, INSTANCE_CONNECT);
        assertThat(RoleTemplate.ATENDENTE.permissions()).doesNotContain(INSTANCE_CONNECT, USERS_MANAGE);
        assertThat(RoleTemplate.OPERADOR.permissions()).doesNotContain(INSTANCE_DELETE, USERS_MANAGE, ROLES_MANAGE);
    }

    @Test
    void formPermissionsMustComeFromTheCatalog() {
        assertThat(Permission.parse(List.of("INSTANCE_VIEW", " ", "CHAT_VIEW"))).containsExactly(INSTANCE_VIEW, CHAT_VIEW);
        assertThat(Permission.parse(null)).isEmpty();
        assertThatThrownBy(() -> Permission.parse(List.of("SUPERPODER"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccessAdminService.parsePermissions(List.of("SUPERPODER")))
                .isInstanceOf(AccessAdminService.AccessRuleException.class);
    }

    @Test
    void everyPermissionBelongsToExactlyOneGroup() {
        var grouped = EnumSet.noneOf(Permission.class);
        for (var group : Permission.Group.values()) {
            group.permissions().forEach(p -> assertThat(grouped.add(p)).as("%s em dois grupos", p).isTrue());
        }
        assertThat(grouped).isEqualTo(Permission.all());
    }
}
