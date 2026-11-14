package dev.msgforge.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.msgforge.access.AccessAdminService.AccessRuleException;
import dev.msgforge.instance.InstanceRepository;
import dev.msgforge.support.AccessFixtures;
import dev.msgforge.support.IntegrationTest;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

/** Modelo de acesso (ADR 0010) no Postgres: migration, papéis-modelo, "pode X em Y" e travas. */
class AccessModelIT extends IntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired AccessService access;
    @Autowired AccessAdminService admin;
    @Autowired InstanceRepository instances;
    @Autowired MembershipRepository memberships;
    @Autowired TransactionTemplate tx;

    private static final ManagerPrincipal KEY = ManagerPrincipal.GLOBAL_KEY;

    @BeforeEach
    @AfterEach
    void resetAccess() {
        AccessFixtures.reset(jdbc);
    }

    private ManagerPrincipal user(String email, UUID roleId) {
        var created = admin.createUser(KEY, Organization.DEFAULT_ID, email, "senha-inicial-123", roleId, false);
        return new ManagerPrincipal(created.getId(), created.getEmail());
    }

    @Test
    void existingInstancesMoveToTheDefaultOrganization() {
        // Banco num schema próprio, migrado até a versão anterior, com uma instância "antiga"
        var schema = "migracao_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration")
                    .target("202611140045").load().migrate();
            var old = UUID.randomUUID();
            jdbc.update("INSERT INTO " + schema + ".instance (id, name, token) VALUES (?, 'antiga', 'token-antigo')", old);

            Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration").load().migrate();

            assertThat(jdbc.queryForObject("SELECT organization_id FROM " + schema + ".instance WHERE id = ?",
                    UUID.class, old)).isEqualTo(Organization.DEFAULT_ID);
            assertThat(jdbc.queryForObject("SELECT name FROM " + schema + ".organization WHERE id = ?",
                    String.class, Organization.DEFAULT_ID)).isEqualTo("Padrão");
            // Organização com instâncias não é apagada (fk_instance_organization RESTRICT)
            assertThatThrownBy(() -> jdbc.update("DELETE FROM " + schema + ".organization"))
                    .hasMessageContaining("fk_instance_organization");
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void theDefaultOrganizationHasTheTemplateRoles() {
        for (var template : RoleTemplate.values()) {
            var perms = jdbc.queryForList("""
                    SELECT rp.permission FROM role r JOIN role_permission rp ON rp.role_id = r.id
                    WHERE r.organization_id = ? AND r.name = ?""", String.class,
                    Organization.DEFAULT_ID, template.roleName());
            assertThat(perms).as(template.roleName())
                    .containsExactlyInAnyOrderElementsOf(template.permissions().stream().map(Enum::name).toList());
        }
    }

    @Test
    void instancesCreatedByTheApiBelongToTheDefaultOrganization() {
        var name = uniqueName("api");
        assertThat(post("/instance/create", "{\"instanceName\":\"" + name + "\"}").status()).isEqualTo(201);
        assertThat(instances.findByName(name).orElseThrow().getOrganizationId()).isEqualTo(Organization.DEFAULT_ID);
    }

    @Test
    void canXOnInstanceYFollowsTheRoleTheOrganizationAndTheScope() {
        var name = uniqueName("vendas");
        post("/instance/create", "{\"instanceName\":\"" + name + "\"}");
        var sales = instances.findByName(name).orElseThrow();
        var client = user("cliente@exemplo.com", AccessFixtures.CLIENTE);

        assertThat(access.can(client, Permission.INSTANCE_CONNECT, sales)).isTrue();
        assertThat(access.can(client, Permission.INSTANCE_DELETE, sales)).isFalse();
        assertThat(access.can(KEY, Permission.INSTANCE_DELETE, sales)).isTrue();

        // Escopo: o vínculo passa a valer só para outra instância
        var other = UUID.randomUUID();
        jdbc.update("INSERT INTO instance (id, name, token) VALUES (?, ?, ?)", other, uniqueName("outra"),
                UUID.randomUUID().toString());
        tx.executeWithoutResult(status -> memberships
                .findByUserIdAndOrganizationId(client.userId(), Organization.DEFAULT_ID).orElseThrow()
                .limitTo(Set.of(other)));
        assertThat(access.can(client, Permission.INSTANCE_CONNECT, sales)).isFalse();

        // Bloqueado não pode nada
        jdbc.update("UPDATE app_user SET status = 'BLOQUEADO' WHERE id = ?", client.userId());
        assertThat(access.can(client, Permission.INSTANCE_VIEW, sales)).isFalse();
        assertThat(access.isActive(client)).isFalse();
    }

    @Test
    void emailIsUniqueIgnoringCaseAndThePasswordIsStoredOnlyAsAHash() {
        user("Ana@Exemplo.com", AccessFixtures.OPERADOR);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE email = 'ana@exemplo.com'", String.class))
                .startsWith("{bcrypt}").doesNotContain("senha-inicial-123");
        assertThatThrownBy(() -> user("ANA@exemplo.com", AccessFixtures.CLIENTE))
                .isInstanceOf(AccessRuleException.class).hasMessage("manager.access.error.emailTaken");
    }

    @Test
    void nobodyGrantsWhatTheyDoNotHave() {
        var supervisorRole = admin.createRole(KEY, Organization.DEFAULT_ID, "Supervisor",
                EnumSet.of(Permission.USERS_MANAGE, Permission.INSTANCE_VIEW));
        var supervisor = user("supervisor@exemplo.com", supervisorRole.getId());

        assertThatThrownBy(() -> admin.createUser(supervisor, Organization.DEFAULT_ID, "x@exemplo.com",
                "senha-inicial-123", AccessFixtures.GESTOR, false))
                .hasMessage("manager.access.error.escalation");
        assertThatThrownBy(() -> admin.createUser(supervisor, Organization.DEFAULT_ID, "x@exemplo.com",
                "senha-inicial-123", AccessFixtures.CLIENTE, false))   // CLIENTE tem "conectar", que ele não tem
                .hasMessage("manager.access.error.escalation");
        assertThatThrownBy(() -> admin.createUser(supervisor, Organization.DEFAULT_ID, "x@exemplo.com",
                "senha-inicial-123", supervisorRole.getId(), true))
                .hasMessage("manager.access.error.adminOnly");
        // Papel com o que ele tem: aceito
        admin.createUser(supervisor, Organization.DEFAULT_ID, "x@exemplo.com", "senha-inicial-123",
                supervisorRole.getId(), false);
        // Sem "gerenciar papéis", não mexe em papel nenhum
        assertThatThrownBy(() -> admin.createRole(supervisor, Organization.DEFAULT_ID, "Outro", EnumSet.noneOf(Permission.class)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theOrganizationKeepsARoleManagerAndRolesInUseStay() {
        var boss = user("gestor@exemplo.com", AccessFixtures.GESTOR);

        assertThatThrownBy(() -> admin.changeRole(KEY, Organization.DEFAULT_ID, boss.userId(), AccessFixtures.CLIENTE))
                .hasMessage("manager.access.error.lastRoleManager");
        assertThatThrownBy(() -> admin.setBlocked(KEY, Organization.DEFAULT_ID, boss.userId(), true))
                .hasMessage("manager.access.error.lastRoleManager");
        assertThatThrownBy(() -> admin.updateRole(KEY, Organization.DEFAULT_ID, AccessFixtures.GESTOR, "Gestor",
                EnumSet.of(Permission.INSTANCE_VIEW)))
                .hasMessage("manager.access.error.lastRoleManager");
        assertThatThrownBy(() -> admin.deleteRole(KEY, Organization.DEFAULT_ID, AccessFixtures.GESTOR))
                .hasMessage("manager.access.error.roleInUse");
        assertThatThrownBy(() -> admin.setBlocked(boss, Organization.DEFAULT_ID, boss.userId(), true))
                .hasMessage("manager.access.error.self");

        // Com um segundo gestor, o primeiro pode mudar de papel
        user("gestora2@exemplo.com", AccessFixtures.GESTOR);
        admin.changeRole(KEY, Organization.DEFAULT_ID, boss.userId(), AccessFixtures.CLIENTE);
        assertThat(access.canInOrganization(boss, Permission.USERS_MANAGE, Organization.DEFAULT_ID)).isFalse();
    }

    @Test
    void aNewOrganizationStartsWithTheTemplatesAndItsOwnRolesStayInside() {
        var org = admin.createOrganization(KEY, "Cliente A");
        var roles = admin.roles(KEY, org.getId());
        assertThat(roles).extracting(Role::getName).containsExactlyInAnyOrder("Gestor", "Operador", "Atendente", "Cliente");

        var boss = user("gestor@exemplo.com", AccessFixtures.GESTOR);   // gestor da organização padrão
        assertThat(access.canInOrganization(boss, Permission.USERS_MANAGE, org.getId())).isFalse();
        assertThat(access.organizationsWhere(boss, Permission.USERS_MANAGE)).extracting(Organization::getId)
                .containsExactly(Organization.DEFAULT_ID);
        // Papel de outra organização não serve aqui
        assertThatThrownBy(() -> admin.createUser(KEY, Organization.DEFAULT_ID, "y@exemplo.com", "senha-inicial-123",
                roles.getFirst().getId(), false)).hasMessage("manager.access.error.roleNotFound");
        assertThatThrownBy(() -> admin.createOrganization(boss, "Cliente B")).isInstanceOf(AccessDeniedException.class);
    }
}
