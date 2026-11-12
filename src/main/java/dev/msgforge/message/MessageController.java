package dev.msgforge.message;

import dev.msgforge.gateway.WhatsAppGateway.SentMessage;
import dev.msgforge.history.HistoryRecorder;
import dev.msgforge.instance.Instance;
import dev.msgforge.message.MessageDtos.MessageResponse;
import dev.msgforge.message.MessageDtos.SendContactRequest;
import dev.msgforge.message.MessageDtos.SendLocationRequest;
import dev.msgforge.message.MessageDtos.SendMediaRequest;
import dev.msgforge.message.MessageDtos.SendPollRequest;
import dev.msgforge.message.MessageDtos.SendReactionRequest;
import dev.msgforge.message.MessageDtos.SendWhatsAppAudioRequest;
import dev.msgforge.message.MessageDtos.SendTextRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/message")
public class MessageController {

    private final MessageService messages;
    private final HistoryRecorder history;

    public MessageController(MessageService messages, HistoryRecorder history) {
        this.messages = messages;
        this.history = history;
    }

    @PostMapping("/sendText/{instance}")
    public ResponseEntity<MessageResponse> sendText(@PathVariable String instance, @Valid @RequestBody SendTextRequest body) {
        var sent = messages.sendText(instance, body.number(), body.text(), body.delay(), body.linkPreview());
        return created(sent.instance(), sent.message(), "conversation", Map.of("conversation", body.text()));
    }

    @PostMapping("/sendMedia/{instance}")
    public ResponseEntity<MessageResponse> sendMedia(@PathVariable String instance, @Valid @RequestBody SendMediaRequest body) {
        var sent = messages.sendMedia(instance, body.number(), body.mediatype(), body.mimetype(), body.media(),
                body.caption(), body.fileName(), body.delay());

        var content = new LinkedHashMap<String, Object>();
        if (body.caption() != null) content.put("caption", body.caption());
        if (body.mimetype() != null) content.put("mimetype", body.mimetype());
        if (body.fileName() != null) content.put("fileName", body.fileName());
        var messageType = body.mediatype() + "Message";
        return created(sent.instance(), sent.message(), messageType, Map.of(messageType, content));
    }

    @PostMapping("/sendWhatsAppAudio/{instance}")
    public ResponseEntity<MessageResponse> sendWhatsAppAudio(@PathVariable String instance,
                                                             @Valid @RequestBody SendWhatsAppAudioRequest body) {
        var sent = messages.sendVoice(instance, body.number(), body.audio(), body.delay(), body.encoding());
        return created(sent.instance(), sent.message(), "audioMessage",
                Map.of("audioMessage", Map.of("ptt", true)));
    }

    @PostMapping("/sendLocation/{instance}")
    public ResponseEntity<MessageResponse> sendLocation(@PathVariable String instance,
                                                        @Valid @RequestBody SendLocationRequest body) {
        var sent = messages.sendLocation(instance, body.number(), body.latitude(), body.longitude(),
                body.name(), body.address(), body.delay());
        var content = new LinkedHashMap<String, Object>();
        content.put("degreesLatitude", body.latitude());
        content.put("degreesLongitude", body.longitude());
        if (body.name() != null) content.put("name", body.name());
        if (body.address() != null) content.put("address", body.address());
        return created(sent.instance(), sent.message(), "locationMessage", Map.of("locationMessage", content));
    }

    @PostMapping("/sendContact/{instance}")
    public ResponseEntity<MessageResponse> sendContact(@PathVariable String instance,
                                                       @Valid @RequestBody SendContactRequest body) {
        var sent = messages.sendContacts(instance, body.number(), body.contact(), body.delay());
        var cards = body.contact().stream()
                .map(c -> Map.<String, Object>of("displayName", c.fullName(), "vcard", VCards.of(c)))
                .toList();
        if (cards.size() == 1) {
            return created(sent.instance(), sent.message(), "contactMessage", Map.of("contactMessage", cards.getFirst()));
        }
        return created(sent.instance(), sent.message(), "contactsArrayMessage", Map.of("contactsArrayMessage",
                Map.of("displayName", cards.size() + " contacts", "contacts", cards)));
    }

    @PostMapping("/sendReaction/{instance}")
    public ResponseEntity<MessageResponse> sendReaction(@PathVariable String instance,
                                                        @Valid @RequestBody SendReactionRequest body) {
        var key = body.key();
        var fromMe = Boolean.TRUE.equals(key.fromMe());
        var sent = messages.sendReaction(instance, key.remoteJid(), key.id(), fromMe, body.reaction());
        var reacted = Map.<String, Object>of("remoteJid", sent.message().remoteJid(), "fromMe", fromMe, "id", key.id());
        return created(sent.instance(), sent.message(), "reactionMessage",
                Map.of("reactionMessage", Map.of("key", reacted, "text", body.reaction())));
    }

    @PostMapping("/sendPoll/{instance}")
    public ResponseEntity<MessageResponse> sendPoll(@PathVariable String instance,
                                                    @Valid @RequestBody SendPollRequest body) {
        var sent = messages.sendPoll(instance, body.number(), body.name(), body.selectableCount(), body.values(),
                body.delay());
        var options = body.values().stream().map(v -> Map.<String, Object>of("optionName", v)).toList();
        var count = body.selectableCount() == null ? 0 : body.selectableCount();
        return created(sent.instance(), sent.message(), "pollCreationMessage", Map.of("pollCreationMessage",
                Map.of("name", body.name(), "options", options, "selectableOptionsCount", count)));
    }

    /** Resposta do envio; a mesma mensagem vai para o histórico. */
    private ResponseEntity<MessageResponse> created(Instance instance, SentMessage sent, String type,
                                                    Map<String, Object> message) {
        history.recordSent(instance, sent, type, message);
        var response = new MessageResponse(
                new MessageResponse.Key(sent.remoteJid(), true, sent.id()),
                null, "PENDING", message, type, sent.timestamp(), instance.getId().toString(), "msgforge");
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
