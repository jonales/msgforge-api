package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Rotas /group/* com o motor stub (que guarda os grupos em memória). */
class GroupIT extends IntegrationTest {

    private static final String ANA = "5561911111111@s.whatsapp.net";
    private static final String BIA = "5561922222222@s.whatsapp.net";

    private String name;

    @BeforeEach
    void createInstance() {
        name = uniqueName("grupos");
        assertThat(post("/instance/create", """
                {"instanceName":"%s"}""".formatted(name)).status()).isEqualTo(201);
    }

    private String path(String route, String groupJid) {
        return "/group/" + route + "/" + name + (groupJid == null ? "" : "?groupJid=" + groupJid);
    }

    private String createGroup() {
        var created = post(path("create", null), """
                {"subject":" Família ","description":"Regras","participants":["+55 61 91111-1111","5561911111111","%s"]}"""
                .formatted(BIA));
        assertThat(created.status()).isEqualTo(201);
        return created.body().get("id").asString();
    }

    private void assertBadRequest(Response response) {
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().get("response").get("message").isArray()).isTrue();
    }

    @Test
    void createAndFind() {
        var id = createGroup();
        assertThat(id).endsWith("@g.us");

        var info = get(path("findGroupInfos", id));
        assertThat(info.status()).isEqualTo(200);
        var group = info.body();
        assertThat(group.get("subject").asString()).isEqualTo("Família");
        assertThat(group.get("desc").asString()).isEqualTo("Regras");
        assertThat(group.get("size").asInt()).isEqualTo(3);   // a conta + Ana + Bia (Ana repetida conta uma vez)
        assertThat(group.get("participants").get(0).get("admin").asString()).isEqualTo("superadmin");
        assertThat(group.get("participants").get(1).get("admin").isNull()).isTrue();
        assertThat(group.has("pictureUrl")).isTrue();
        assertThat(group.get("announce").asBoolean()).isFalse();

        var all = get(path("fetchAllGroups", null));
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.body().size()).isEqualTo(1);
        assertThat(all.body().get(0).has("participants")).isFalse();
        assertThat(get(path("fetchAllGroups", null) + "?getParticipants=true").body().get(0).has("participants")).isTrue();

        var participants = get(path("participants", id));
        assertThat(participants.body().get("participants").size()).isEqualTo(3);
    }

    @Test
    void participantsSubjectDescriptionAndSettings() {
        var id = createGroup();

        var promote = post(path("updateParticipant", id), """
                {"action":"promote","participants":["%s","5561988888888"]}""".formatted(ANA));
        assertThat(promote.status()).isEqualTo(201);
        var statuses = promote.body().get("updateParticipants");
        assertThat(statuses.get(0).get("jid").asString()).isEqualTo(ANA);
        assertThat(statuses.get(0).get("status").asString()).isEqualTo("200");
        assertThat(statuses.get(1).get("status").asString()).isEqualTo("400");   // não está no grupo

        assertThat(post(path("updateParticipant", id), """
                {"action":"remove","participants":["%s"]}""".formatted(BIA)).status()).isEqualTo(201);
        assertThat(post(path("updateGroupSubject", id), """
                {"subject":"Família Silva"}""").body().get("update").asString()).isEqualTo("success");
        assertThat(post(path("updateGroupDescription", id), """
                {"description":"Novas regras"}""").status()).isEqualTo(201);
        assertThat(post(path("updateSetting", id), """
                {"action":"announcement"}""").status()).isEqualTo(201);
        assertThat(post(path("updateSetting", id), """
                {"action":"locked"}""").status()).isEqualTo(201);
        assertThat(post(path("updateGroupPicture", id), """
                {"image":"https://example.com/foto.jpg"}""").status()).isEqualTo(201);

        var group = get(path("findGroupInfos", id)).body();
        assertThat(group.get("subject").asString()).isEqualTo("Família Silva");
        assertThat(group.get("desc").asString()).isEqualTo("Novas regras");
        assertThat(group.get("announce").asBoolean()).isTrue();
        assertThat(group.get("restrict").asBoolean()).isTrue();
        assertThat(group.get("size").asInt()).isEqualTo(2);
        assertThat(group.get("participants").get(1).get("admin").asString()).isEqualTo("admin");

        assertBadRequest(post(path("updateParticipant", id), """
                {"action":"kick","participants":["%s"]}""".formatted(ANA)));
        assertBadRequest(post(path("updateParticipant", id), """
                {"action":"add","participants":["%s"]}""".formatted(id)));   // grupo não é participante
        assertBadRequest(post(path("updateSetting", id), """
                {"action":"open"}"""));
    }

    @Test
    void inviteRevokeAcceptAndLeave() {
        var id = createGroup();

        var invite = get(path("inviteCode", id));
        assertThat(invite.status()).isEqualTo(200);
        var code = invite.body().get("inviteCode").asString();
        assertThat(invite.body().get("inviteUrl").asString()).isEqualTo("https://chat.whatsapp.com/" + code);

        var revoked = post(path("revokeInviteCode", id), "{}");
        assertThat(revoked.status()).isEqualTo(201);
        assertThat(revoked.body().get("revoked").asBoolean()).isTrue();
        var newCode = revoked.body().get("inviteCode").asString();
        assertThat(newCode).isNotEqualTo(code);

        var accepted = get("/group/acceptInviteCode/" + name + "?inviteCode=https://chat.whatsapp.com/" + newCode);
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(accepted.body().get("groupJid").asString()).isEqualTo(id);
        assertBadRequest(get("/group/acceptInviteCode/" + name + "?inviteCode=a/b"));

        var left = delete(path("leaveGroup", id));
        assertThat(left.status()).isEqualTo(200);
        assertThat(left.body().get("leave").asBoolean()).isTrue();
        assertThat(get(path("findGroupInfos", id)).status()).isEqualTo(404);
    }

    @Test
    void groupJidIsValidated() {
        assertBadRequest(get(path("findGroupInfos", null)));
        assertBadRequest(get(path("findGroupInfos", "5561999999999")));   // é telefone, não grupo
        assertThat(get(path("findGroupInfos", "120363999999999999@g.us")).status()).isEqualTo(404);
        assertBadRequest(post(path("create", null), """
                {"subject":"G","participants":[]}"""));
    }
}
