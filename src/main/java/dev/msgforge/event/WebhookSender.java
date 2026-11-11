package dev.msgforge.event;

import dev.msgforge.config.MsgforgeProperties;
import dev.msgforge.security.PrivateNetworkGuard;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST de um envelope de evento com retry exponencial.
 * O corpo é serializado uma vez para bytes, então sai com Content-Length (sem chunked).
 * Deve ser chamado fora da thread do request: as esperas entre tentativas bloqueiam.
 */
@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

    private final RestClient http;
    private final JsonMapper json;
    private final MsgforgeProperties.Retry retry;
    private final PrivateNetworkGuard guard;

    public WebhookSender(RestClient webhookRestClient, JsonMapper json, MsgforgeProperties properties,
                         PrivateNetworkGuard guard) {
        this.http = webhookRestClient;
        this.json = json;
        this.retry = properties.webhook().retry();
        this.guard = guard;
    }

    /** Destino configurado pelo operador (webhook global): sem checagem de rede privada. */
    public boolean send(String url, String event, Object body, Map<String, String> headers) {
        return deliver(url, event, body, headers);
    }

    /** Destino configurado por quem tem o token da instância: aplica {@link PrivateNetworkGuard}. */
    public boolean sendUntrusted(String url, String event, Object body, Map<String, String> headers) {
        var refusal = guard.check(url);
        if (refusal.isPresent()) {
            log.warn("Webhook {} para {} bloqueado: {}", event, url, refusal.get());
            return false;
        }
        return deliver(url, event, body, headers);
    }

    /** @return true se algum envio recebeu 2xx */
    private boolean deliver(String url, String event, Object body, Map<String, String> headers) {
        var bytes = json.writeValueAsBytes(body);
        var attempts = Math.max(1, retry.maxAttempts());
        var delay = Math.max(0, retry.initialDelayMs());

        for (int attempt = 1; attempt <= attempts; attempt++) {
            String failure;
            try {
                var status = http.post().uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .headers(h -> headers.forEach(h::set))
                        .body(bytes)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (request, response) -> {})
                        .toBodilessEntity()
                        .getStatusCode();
                if (status.is2xxSuccessful()) {
                    return true;
                }
                if (!retryable(status)) {
                    log.warn("Webhook {} para {} recusado com {} (sem retry)", event, url, status.value());
                    return false;
                }
                failure = "HTTP " + status.value();
            } catch (RestClientException e) {
                failure = e.getMessage();
            }

            if (attempt == attempts) {
                log.warn("Webhook {} para {} falhou após {} tentativa(s): {}", event, url, attempts, failure);
                return false;
            }
            log.debug("Webhook {} para {} falhou (tentativa {}/{}): {}", event, url, attempt, attempts, failure);
            if (!sleep(delay)) {
                return false;
            }
            delay = Math.min(delay * 2, retry.maxDelayMs());
        }
        return false;
    }

    static boolean retryable(HttpStatusCode status) {
        return status.is5xxServerError() || status.value() == 408 || status.value() == 429;
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
