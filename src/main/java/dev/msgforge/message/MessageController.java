package dev.msgforge.message;

import dev.msgforge.gateway.Jids;
import dev.msgforge.gateway.WhatsAppGateway;
import dev.msgforge.gateway.WhatsAppGateway.OutgoingMedia;
import dev.msgforge.gateway.WhatsAppGateway.OutgoingText;
import dev.msgforge.gateway.WhatsAppGateway.SentMessage;
import dev.msgforge.instance.Instance;
import dev.msgforge.instance.InstanceService;
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

    private final InstanceService instances;
    private final WhatsAppGateway gateway;

    public MessageController(InstanceService instances, WhatsAppGateway gateway) {
        this.instances = instances;
        this.gateway = gateway;
    }

    @PostMapping("/sendText/{instance}")
    public ResponseEntity<MessageResponse> sendText(@PathVariable String instance, @Valid @RequestBody SendTextRequest body) {
        var target = instances.get(instance);
        var sent = gateway.sendText(target,
                new OutgoingText(Jids.toJid(body.number()), body.text(), body.delay(), body.linkPreview()));
        return created(target, sent, "conversation", Map.of("conversation", body.text()));
    }

    @PostMapping("/sendMedia/{instance}")
    public ResponseEntity<MessageResponse> sendMedia(@PathVariable String instance, @Valid @RequestBody SendMediaRequest body) {
        var target = instances.get(instance);
        var sent = gateway.sendMedia(target, new OutgoingMedia(Jids.toJid(body.number()), body.mediatype(),
                body.mimetype(), body.media(), body.caption(), body.fileName(), body.delay()));

        var content = new LinkedHashMap<String, Object>();
        if (body.caption() != null) content.put("caption", body.caption());
        if (body.mimetype() != null) content.put("mimetype", body.mimetype());
        if (body.fileName() != null) content.put("fileName", body.fileName());
        var messageType = body.mediatype() + "Message";
        return created(target, sent, messageType, Map.of(messageType, content));
    }

    private static ResponseEntity<MessageResponse> created(Instance instance, SentMessage sent, String type,
                                                           Map<String, Object> message) {
        var response = new MessageResponse(
                new MessageResponse.Key(sent.remoteJid(), true, sent.id()),
                null, "PENDING", message, type, sent.timestamp(), instance.getId().toString(), "msgforge");
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
