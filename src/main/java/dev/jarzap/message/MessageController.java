package dev.jarzap.message;

import dev.jarzap.gateway.Jids;
import dev.jarzap.gateway.WhatsAppGateway;
import dev.jarzap.gateway.WhatsAppGateway.OutgoingMedia;
import dev.jarzap.gateway.WhatsAppGateway.OutgoingText;
import dev.jarzap.gateway.WhatsAppGateway.SentMessage;
import dev.jarzap.instance.Instance;
import dev.jarzap.instance.InstanceService;
import dev.jarzap.message.MessageDtos.MessageResponse;
import dev.jarzap.message.MessageDtos.SendMediaRequest;
import dev.jarzap.message.MessageDtos.SendTextRequest;
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
                null, "PENDING", message, type, sent.timestamp(), instance.getId().toString(), "jarzap");
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
