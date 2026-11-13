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

    /** Resultado de uma tentativa só (usada pela outbox, que agenda as novas tentativas). */
    public sealed interface Attempt {
        record Delivered() implements Attempt {}

        /** Erro de rede, 408, 429 ou 5xx: vale tentar de novo. */
        record Retry(String reason) implements Attempt {}

        /** Recusa definitiva (outro 4xx, ou rede privada bloqueada): não adianta repetir. */
        record Failed(String reason) implements Attempt {}
    }

    /**
     * Uma tentativa, sem espera nem retry. O corpo já vem serializado (snapshot gravado na outbox).
     *
     * @param checkPrivateNetwork aplica a checagem de rede privada mesmo que a proteção deste nó
     *                            esteja desligada (a decisão foi tomada quando o evento aconteceu)
     */
    public Attempt attempt(String url, String event, byte[] body, Map<String, String> headers,
                           boolean checkPrivateNetwork) {
        if (checkPrivateNetwork) {
            var refusal = guard.inspect(url);
            if (refusal.isPresent()) {
                log.warn("Webhook {} para {} bloqueado: {}", event, url, refusal.get());
                return new Attempt.Failed("blocked: " + refusal.get());
            }
        }
        return post(url, body, headers);
    }

    /** Total de tentativas da política de retry (WEBHOOK_RETRY_MAX_ATTEMPTS). */
    public int maxAttempts() {
        return Math.max(1, retry.maxAttempts());
    }

    /** Espera antes da tentativa seguinte, depois de {@code failedAttempts} falhas (1, 2, ...). */
    public long retryDelayMs(int failedAttempts) {
        long delay = Math.max(0, retry.initialDelayMs());
        for (int i = 1; i < failedAttempts && delay < retry.maxDelayMs(); i++) {
            delay = delay * 2;
        }
        return Math.min(delay, Math.max(0, retry.maxDelayMs()));
    }

    /** @return true se algum envio recebeu 2xx */
    private boolean deliver(String url, String event, Object body, Map<String, String> headers) {
        var bytes = json.writeValueAsBytes(body);
        var attempts = maxAttempts();

        for (int attempt = 1; attempt <= attempts; attempt++) {
            var result = post(url, bytes, headers);
            String failure;
            switch (result) {
                case Attempt.Delivered _ -> {
                    return true;
                }
                case Attempt.Failed f -> {
                    log.warn("Webhook {} para {} recusado com {} (sem retry)", event, url, f.reason());
                    return false;
                }
                case Attempt.Retry r -> failure = r.reason();
            }

            if (attempt == attempts) {
                log.warn("Webhook {} para {} falhou após {} tentativa(s): {}", event, url, attempts, failure);
                return false;
            }
            log.debug("Webhook {} para {} falhou (tentativa {}/{}): {}", event, url, attempt, attempts, failure);
            if (!sleep(retryDelayMs(attempt))) {
                return false;
            }
        }
        return false;
    }

    private Attempt post(String url, byte[] bytes, Map<String, String> headers) {
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
                return new Attempt.Delivered();
            }
            return retryable(status)
                    ? new Attempt.Retry("HTTP " + status.value())
                    : new Attempt.Failed("HTTP " + status.value());
        } catch (RestClientException e) {
            return new Attempt.Retry(String.valueOf(e.getMessage()));
        }
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
