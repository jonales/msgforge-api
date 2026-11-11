package dev.msgforge.webhook;

import dev.msgforge.webhook.WebhookDtos.SetWebhookRequest;
import dev.msgforge.webhook.WebhookDtos.WebhookResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private final WebhookService service;

    public WebhookController(WebhookService service) {
        this.service = service;
    }

    @PostMapping("/set/{instance}")
    public ResponseEntity<WebhookResponse> set(@PathVariable String instance, @Valid @RequestBody SetWebhookRequest body) {
        return ResponseEntity.status(201).body(service.set(instance, body.webhook()));
    }

    @GetMapping("/find/{instance}")
    public ResponseEntity<WebhookResponse> find(@PathVariable String instance) {
        // A Evolution responde 200 com corpo nulo quando não há webhook
        return ResponseEntity.ok(service.find(instance).orElse(null));
    }
}
