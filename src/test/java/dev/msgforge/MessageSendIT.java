package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Rotas de envio além de texto e mídia: áudio de voz, localização, contato, reação e enquete. */
class MessageSendIT extends IntegrationTest {

    private String name;

    @BeforeEach
    void createInstance() {
        name = uniqueName("envios");
        var created = post("/instance/create", """
                {"instanceName":"%s","webhook":{"url":"%s","byEvents":true,"events":["SEND_MESSAGE"]}}
                """.formatted(name, webhooks.url("/" + name)));
        assertThat(created.status()).isEqualTo(201);
    }

    private Response send(String route, String json) {
        return post("/message/" + route + "/" + name, json);
    }

    private void assertSent(Response response, String messageType) {
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().get("messageType").asString()).isEqualTo(messageType);
        assertThat(response.body().get("key").get("fromMe").asBoolean()).isTrue();
        assertThat(response.body().get("message").has(messageType)).isTrue();
        var id = response.body().get("key").get("id").asString();
        // Todo envio também sai como evento send.message
        webhooks.await(r -> r.path().equals("/" + name + "/send-message")
                && parse(r.body()).get("data").get("key").get("id").asString().equals(id), java.time.Duration.ofSeconds(5));
    }

    private void assertBadRequest(Response response) {
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().get("response").get("message").isArray()).isTrue();
    }

    @Test
    void voiceNote() {
        assertSent(send("sendWhatsAppAudio", """
                {"number":"5561999999999","audio":"T2dnUw=="}"""), "audioMessage");
        assertBadRequest(send("sendWhatsAppAudio", """
                {"number":"5561999999999"}"""));
    }

    @Test
    void location() {
        var sent = send("sendLocation", """
                {"number":"5561999999999","latitude":-15.7998,"longitude":-47.8645,"name":"Esplanada"}""");
        assertSent(sent, "locationMessage");
        assertThat(sent.body().get("message").get("locationMessage").get("degreesLatitude").asDouble()).isEqualTo(-15.7998);

        assertBadRequest(send("sendLocation", """
                {"number":"5561999999999","latitude":91,"longitude":0}"""));
        assertBadRequest(send("sendLocation", """
                {"number":"5561999999999","longitude":0}"""));
    }

    @Test
    void oneContactOrSeveral() {
        var one = send("sendContact", """
                {"number":"5561999999999","contact":[{"fullName":"Ana\\nTEL;waid=9:+9","phoneNumber":"5561988887777"}]}""");
        assertSent(one, "contactMessage");
        var vcard = one.body().get("message").get("contactMessage").get("vcard").asString();
        assertThat(vcard.lines()).filteredOn(l -> l.startsWith("TEL")).hasSize(1);   // sem injeção de campos

        assertSent(send("sendContact", """
                {"number":"5561999999999","contact":[
                  {"fullName":"Ana","phoneNumber":"5561988887777"},
                  {"fullName":"Bia","phoneNumber":"5561977776666","organization":"ACME"}]}"""), "contactsArrayMessage");

        assertBadRequest(send("sendContact", """
                {"number":"5561999999999","contact":[]}"""));
        assertBadRequest(send("sendContact", """
                {"number":"5561999999999","contact":[{"fullName":"Sem telefone"}]}"""));
    }

    @Test
    void reaction() {
        var sent = send("sendReaction", """
                {"key":{"remoteJid":"5561999999999","fromMe":false,"id":"3EB0ABCDEF"},"reaction":"👍"}""");
        assertSent(sent, "reactionMessage");
        var reacted = sent.body().get("message").get("reactionMessage");
        assertThat(reacted.get("text").asString()).isEqualTo("👍");
        assertThat(reacted.get("key").get("remoteJid").asString()).isEqualTo("5561999999999@s.whatsapp.net");

        // Reação vazia remove a reação: é válida
        assertSent(send("sendReaction", """
                {"key":{"remoteJid":"5561999999999","id":"3EB0ABCDEF"},"reaction":""}"""), "reactionMessage");
        assertBadRequest(send("sendReaction", """
                {"reaction":"👍"}"""));
    }

    @Test
    void poll() {
        var sent = send("sendPoll", """
                {"number":"5561999999999","name":"Almoço?","selectableCount":1,"values":["Sim","Não"]}""");
        assertSent(sent, "pollCreationMessage");
        var poll = sent.body().get("message").get("pollCreationMessage");
        assertThat(poll.get("options").size()).isEqualTo(2);
        assertThat(poll.get("selectableOptionsCount").asInt()).isEqualTo(1);

        assertBadRequest(send("sendPoll", """
                {"number":"5561999999999","name":"Só uma","values":["Sim"]}"""));
        assertBadRequest(send("sendPoll", """
                {"number":"5561999999999","name":"Repetida","values":["Sim","Sim"]}"""));
        assertBadRequest(send("sendPoll", """
                {"number":"5561999999999","name":"Negativa","selectableCount":-1,"values":["A","B"]}"""));
    }

    @Test
    void unknownInstanceAndInvalidNumber() {
        assertThat(post("/message/sendLocation/nao-existe", """
                {"number":"5561999999999","latitude":0,"longitude":0}""").status()).isEqualTo(404);
        assertBadRequest(send("sendPoll", """
                {"number":"abc","name":"X","values":["A","B"]}"""));
    }
}
