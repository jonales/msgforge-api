package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Rotas /chat/* (conversas e perfil) com o motor stub. */
class ChatIT extends IntegrationTest {

    private String name;
    private String token;

    @BeforeEach
    void createInstance() {
        name = uniqueName("chat");
        var created = post("/instance/create", """
                {"instanceName":"%s"}""".formatted(name));
        assertThat(created.status()).isEqualTo(201);
        token = created.body().get("hash").asString();
    }

    private Response chat(String route, String json) {
        return post("/chat/" + route + "/" + name, json);
    }

    private void assertBadRequest(Response response) {
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().get("response").get("message").isArray()).isTrue();
    }

    @Test
    void whatsappNumbersKeepsOrderAndMarksInvalidOnesAsMissing() {
        var response = chat("whatsappNumbers", """
                {"numbers":["+55 (61) 99999-9999","abc","120363000000000000@g.us","5561999999999"]}""");

        assertThat(response.status()).isEqualTo(200);
        var list = response.body();
        assertThat(list.size()).isEqualTo(4);   // o repetido (mesmo JID) também tem resposta
        assertThat(list.get(3).get("number").asString()).isEqualTo("5561999999999");
        assertThat(list.get(3).get("exists").asBoolean()).isTrue();
        assertThat(list.get(0).get("exists").asBoolean()).isTrue();
        assertThat(list.get(0).get("jid").asString()).isEqualTo("5561999999999@s.whatsapp.net");
        assertThat(list.get(0).get("number").asString()).isEqualTo("+55 (61) 99999-9999");
        assertThat(list.get(1).get("exists").asBoolean()).isFalse();
        assertThat(list.get(1).get("jid").isNull()).isTrue();
        assertThat(list.get(2).get("exists").asBoolean()).isFalse();   // grupo não é número

        assertBadRequest(chat("whatsappNumbers", """
                {"numbers":[]}"""));
    }

    @Test
    void markMessageAsRead() {
        var response = chat("markMessageAsRead", """
                {"readMessages":[{"remoteJid":"5561999999999@s.whatsapp.net","fromMe":false,"id":"3EB0ABC"}]}""");

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().get("message").asString()).isEqualTo("Read messages");
        assertThat(response.body().get("read").asString()).isEqualTo("success");

        assertBadRequest(chat("markMessageAsRead", """
                {"readMessages":[{"remoteJid":"5561999999999@s.whatsapp.net"}]}"""));
    }

    @Test
    void sendPresence() {
        var response = chat("sendPresence", """
                {"number":"5561999999999","presence":"Composing","delay":10}""");

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().get("presence").asString()).isEqualTo("composing");

        assertBadRequest(chat("sendPresence", """
                {"number":"5561999999999","presence":"dancing"}"""));
    }

    @Test
    void profileQueries() {
        var picture = chat("fetchProfilePictureUrl", """
                {"number":"5561999999999"}""");
        assertThat(picture.status()).isEqualTo(200);
        assertThat(picture.body().get("wuid").asString()).isEqualTo("5561999999999@s.whatsapp.net");
        assertThat(picture.body().get("profilePictureUrl").isNull()).isTrue();   // sem foto: null

        var profile = chat("fetchProfile", """
                {"number":"5561999999999"}""");
        assertThat(profile.status()).isEqualTo(200);
        assertThat(profile.body().get("wuid").asString()).isEqualTo("5561999999999@s.whatsapp.net");
        assertThat(profile.body().get("numberExists").asBoolean()).isTrue();
        assertThat(profile.body().has("picture")).isTrue();   // presente, mesmo nulo

        // Sem número, o perfil é o da própria conta: a instância nunca conectou
        assertBadRequest(chat("fetchProfile", "{}"));
    }

    @Test
    void profileUpdates() {
        for (var route : new String[] {"updateProfileName", "updateProfileStatus", "updateProfilePicture"}) {
            var field = switch (route) {
                case "updateProfileName" -> "name";
                case "updateProfileStatus" -> "status";
                default -> "picture";
            };
            var response = chat(route, """
                    {"%s":"valor"}""".formatted(field));
            assertThat(response.status()).as(route).isEqualTo(200);
            assertThat(response.body().get("update").asString()).isEqualTo("success");
            assertBadRequest(chat(route, "{}"));
        }
        assertBadRequest(chat("updateProfileName", """
                {"name":"%s"}""".formatted("x".repeat(26))));
    }

    @Test
    void instanceTokenWorksAndUnknownInstanceIs404() {
        var withToken = call("POST", "/chat/whatsappNumbers/" + name, token, """
                {"numbers":["5561999999999"]}""");
        assertThat(withToken.status()).isEqualTo(200);

        var missing = post("/chat/whatsappNumbers/nao-existe-" + name, """
                {"numbers":["5561999999999"]}""");
        assertThat(missing.status()).isEqualTo(404);
    }
}
