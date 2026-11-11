package dev.msgforge.message;

import dev.msgforge.gateway.WhatsAppGateway.SentMessage;
import dev.msgforge.instance.Instance;
import dev.msgforge.message.MessageDtos.MessageResponse;
import dev.msgforge.message.MessageDtos.SendMediaRequest;
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

    public MessageController(MessageService messages) {
        this.messages = messages;
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

    private static ResponseEntity<MessageResponse> created(Instance instance, SentMessage sent, String type,
                                                           Map<String, Object> message) {
        var response = new MessageResponse(
                new MessageResponse.Key(sent.remoteJid(), true, sent.id()),
                null, "PENDING", message, type, sent.timestamp(), instance.getId().toString(), "msgforge");
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
