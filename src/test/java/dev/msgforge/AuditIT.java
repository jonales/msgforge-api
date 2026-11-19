// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.msgforge.access.AccessAdminService;
import dev.msgforge.access.AccessAdminService.AccessRuleException;
import dev.msgforge.access.ManagerPrincipal;
import dev.msgforge.access.Organization;
import dev.msgforge.access.Permission;
import dev.msgforge.audit.AuditRetention;
import dev.msgforge.support.AccessFixtures;
import dev.msgforge.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Auditoria do Manager (ADR 0018): login, ações de pessoas, a tela e a retenção. */
@AutoConfigureMockMvc
class AuditIT extends IntegrationTest {

    private static final String PASSWORD = "senha-inicial-123";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessAdminService admin;
    @Autowired AuditRetention retention;

    @BeforeEach
    @AfterEach
    void resetAccess() {
        AccessFixtures.reset(jdbc);
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private UUID createUser(String email, UUID roleId) {
        return admin.createUser(ManagerPrincipal.GLOBAL_KEY, Organization.DEFAULT_ID, email, PASSWORD, roleId, false)
                .getId();
    }

    private MockHttpSession login(String email, String ip) throws Exception {
        var result = mvc.perform(MockMvcRequestBuilders.post("/manager/login").with(csrf()).with(from(ip))
                        .param("username", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/manager")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private void attempt(String email, String password, String ip, String expected) throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/manager/login").with(csrf()).with(from(ip))
                        .param("username", email).param("password", password))
                .andExpect(redirectedUrl(expected));
    }

    private List<Map<String, Object>> rows(String action) {
        return jdbc.queryForList("SELECT * FROM audit_event WHERE action = ? ORDER BY id", action);
    }

    @Test
    void aLoginAndALogoutAreRecordedWithTheIp() throws Exception {
        var id = createUser("ana@exemplo.com", AccessFixtures.OPERADOR);

        var session = login("ana@exemplo.com", "198.51.100.10");
        mvc.perform(MockMvcRequestBuilders.post("/manager/logout").with(csrf()).with(from("198.51.100.10"))
                        .session(session))
                .andExpect(redirectedUrl("/manager/login?logout"));

        var in = rows("LOGIN");
        assertThat(in).hasSize(1);
        assertThat(in.getFirst()).containsEntry("outcome", "OK").containsEntry("actor_user_id", id)
                .containsEntry("actor_label", "ana@exemplo.com").containsEntry("ip", "198.51.100.10");
        assertThat(in.getFirst().get("organization_id")).isNull();
        assertThat(rows("LOGOUT")).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("actor_user_id", id).containsEntry("outcome", "OK"));
    }

    @Test
    void aFailedLoginKeepsTheAccountOnlyWhenItExists() throws Exception {
        var id = createUser("ana@exemplo.com", AccessFixtures.OPERADOR);

        attempt("ana@exemplo.com", "chute-1", "198.51.100.20", "/manager/login?error");
        attempt("segredo-digitado-no-campo@errado", "chute-2", "198.51.100.20", "/manager/login?error");

        var failed = rows("LOGIN");
        assertThat(failed).hasSize(2).allSatisfy(row -> assertThat(row).containsEntry("outcome", "FAILED"));
        assertThat(failed.get(0)).containsEntry("actor_user_id", id).containsEntry("actor_label", "ana@exemplo.com");
        assertThat(failed.get(1)).containsEntry("actor_label", "@desconhecida");
        assertThat(failed.get(1).get("actor_user_id")).isNull();
        // Nem o texto digitado nem as senhas vão para a tabela
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE audit_event::text LIKE '%segredo%' "
                + "OR audit_event::text LIKE '%chute%'", Long.class)).isZero();
    }

    @Test
    void aBlockedIpIsRecordedOncePerWindowAndABlockedUserAsDisabled() throws Exception {
        var id = createUser("ana@exemplo.com", AccessFixtures.OPERADOR);
        var attacker = "203.0.113.150";
        for (int i = 0; i < 10; i++) {
            attempt("ana@exemplo.com", "chute-" + i, attacker, "/manager/login?error");
        }
        for (int i = 0; i < 3; i++) {
            attempt("ana@exemplo.com", PASSWORD, attacker, "/manager/login?blocked");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE outcome = 'BLOCKED' AND ip = ?",
                Long.class, attacker)).isOne();

        createUser("gestor@exemplo.com", AccessFixtures.GESTOR);
        admin.setBlocked(ManagerPrincipal.GLOBAL_KEY, Organization.DEFAULT_ID, id, true);
        attempt("ana@exemplo.com", PASSWORD, "198.51.100.30", "/manager/login?disabled");
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM audit_event WHERE outcome = 'DISABLED'", UUID.class))
                .isEqualTo(id);
    }

    @Test
    void peopleAndRoleChangesAreRecordedOnlyWhenTheyHappen() throws Exception {
        var gestor = admin.createUser(ManagerPrincipal.GLOBAL_KEY, Organization.DEFAULT_ID, "gestor@exemplo.com",
                PASSWORD, AccessFixtures.GESTOR, false);
        var actor = new ManagerPrincipal(gestor.getId(), gestor.getEmail());
        var ana = admin.createUser(actor, Organization.DEFAULT_ID, "ana@exemplo.com", PASSWORD,
                AccessFixtures.ATENDENTE, false).getId();
        admin.changeRole(actor, Organization.DEFAULT_ID, ana, AccessFixtures.OPERADOR);
        admin.setBlocked(actor, Organization.DEFAULT_ID, ana, true);
        admin.setBlocked(actor, Organization.DEFAULT_ID, ana, false);
        var role = admin.createRole(actor, Organization.DEFAULT_ID, "Plantão", Set.of(Permission.INSTANCE_VIEW));
        admin.updateRole(actor, Organization.DEFAULT_ID, role.getId(), "Plantão noturno",
                Set.of(Permission.INSTANCE_VIEW, Permission.CHAT_VIEW));
        admin.deleteRole(actor, Organization.DEFAULT_ID, role.getId());
        // Recusado pelas travas: não entra na auditoria
        try {
            admin.setBlocked(actor, Organization.DEFAULT_ID, gestor.getId(), true);
        } catch (AccessRuleException expected) {
            // a própria pessoa não se bloqueia
        }

        var made = jdbc.queryForList("SELECT action, actor_label, organization_id, target, detail FROM audit_event "
                + "WHERE actor_user_id = ? ORDER BY id", actor.userId());
        assertThat(made).extracting(r -> r.get("action")).containsExactly("USER_CREATED", "USER_ROLE_CHANGED",
                "USER_BLOCKED", "USER_UNBLOCKED", "ROLE_CREATED", "ROLE_UPDATED", "ROLE_DELETED");
        assertThat(made).allSatisfy(r -> assertThat(r).containsEntry("actor_label", "gestor@exemplo.com")
                .containsEntry("organization_id", Organization.DEFAULT_ID));
        assertThat(made.get(1)).containsEntry("target", "ana@exemplo.com")
                .containsEntry("detail", "Atendente → Operador");
        assertThat(made.get(4)).containsEntry("detail", "+INSTANCE_VIEW");
        assertThat(made.get(5)).containsEntry("target", "Plantão → Plantão noturno")
                .containsEntry("detail", "+CHAT_VIEW");
        // Feito pela chave global
        assertThat(jdbc.queryForObject("SELECT actor_label FROM audit_event WHERE target = 'gestor@exemplo.com'",
                String.class)).isEqualTo("@chave");
    }

    @Test
    void theScreenShowsTheOrganizationToItsManagersAndThePlatformToTheAdmin() throws Exception {
        createUser("gestor@exemplo.com", AccessFixtures.GESTOR);
        createUser("operador@exemplo.com", AccessFixtures.OPERADOR);
        attempt("ninguem@exemplo.com", "chute", "198.51.100.40", "/manager/login?error");

        var gestor = login("gestor@exemplo.com", "198.51.100.41");
        mvc.perform(MockMvcRequestBuilders.get("/manager/audit").session(gestor))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/manager/audit\"")))   // link no topo
                .andExpect(content().string(containsString("Criou o usuário")))
                .andExpect(content().string(containsString("operador@exemplo.com")))
                .andExpect(content().string(containsString("198.51.100.41")))
                // tentativa com conta inexistente é só da plataforma
                .andExpect(content().string(not(containsString("Conta inexistente"))));
        mvc.perform(MockMvcRequestBuilders.get("/manager/audit").param("filter", "FAILURES").session(gestor))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nenhum registro com este filtro.")));

        var operador = login("operador@exemplo.com", "198.51.100.42");
        mvc.perform(MockMvcRequestBuilders.get("/manager/audit").session(operador))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get("/manager").session(operador))
                .andExpect(content().string(not(containsString("href=\"/manager/audit\""))));

        var key = (MockHttpSession) mvc.perform(MockMvcRequestBuilders.post("/manager/login").with(csrf())
                        .with(from("198.51.100.43")).param("username", "manager").param("apikey", API_KEY))
                .andExpect(redirectedUrl("/manager")).andReturn().getRequest().getSession(false);
        mvc.perform(MockMvcRequestBuilders.get("/manager/audit").session(key))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Plataforma inteira")))
                .andExpect(content().string(containsString("Conta inexistente")))
                .andExpect(content().string(containsString("Chave da API")));
    }

    @Test
    void retentionDeletesWhatPassedTheDeadline() {
        jdbc.update("INSERT INTO audit_event (at, action, outcome, actor_label) VALUES "
                + "(now() - interval '181 days', 'LOGIN', 'OK', 'velho@exemplo.com'), "
                + "(now() - interval '179 days', 'LOGIN', 'OK', 'novo@exemplo.com')");

        assertThat(retention.purge()).isOne();
        assertThat(jdbc.queryForList("SELECT actor_label FROM audit_event", String.class))
                .containsExactly("novo@exemplo.com");
    }
}
