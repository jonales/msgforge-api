package dev.msgforge.gateway.media;

import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import dev.msgforge.gateway.WhatsAppGateway.GatewayException;
import dev.msgforge.security.PrivateNetworkGuard;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Baixa a mídia que a API recebe como URL ({@code media}, {@code audio}, foto de perfil e de grupo).
 *
 * <p>Diferente do webhook, aqui a checagem de rede privada vale <b>sempre</b>, com ou sem
 * {@code WEBHOOK_BLOCK_PRIVATE_NETWORKS}: o conteúdo baixado volta a quem chamou (vai como mídia
 * para um número que ele escolhe), então uma URL interna seria leitura de serviços da rede e dos
 * metadados de nuvem. Redirects são seguidos aqui, um a um, e cada destino passa de novo pela
 * checagem (no máximo {@link #MAX_REDIRECTS}). Os erros devolvidos não trazem a mensagem da
 * exceção de rede, que serviria para varrer portas.
 *
 * <p>Limite conhecido: a checagem resolve o DNS antes da conexão (DNS rebinding passa).
 */
public final class MediaDownloader {

    private static final Logger log = LoggerFactory.getLogger(MediaDownloader.class);

    static final int MAX_REDIRECTS = 5;
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);

    private final Function<String, Optional<String>> check;
    private final Duration timeout;
    private final HttpClient http;

    /** @param check devolve o motivo da recusa de uma URL, ou vazio se ela pode ser chamada */
    public MediaDownloader(Function<String, Optional<String>> check, Duration timeout) {
        this.check = check;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** A checagem do guard, ligada ou não para os webhooks. */
    public static MediaDownloader guarded(PrivateNetworkGuard guard, Duration timeout) {
        return new MediaDownloader(guard::inspect, timeout);
    }

    /**
     * @param limit quantos bytes ler no máximo (quem chama decide o que fazer quando bate no limite)
     * @throws BadRequestException URL recusada, status diferente de 2xx ou falha de rede
     */
    public byte[] download(String url, int limit) {
        var target = parse(url);
        for (int hop = 0; ; hop++) {
            allow(target);
            HttpResponse<java.io.InputStream> response;
            try {
                response = http.send(HttpRequest.newBuilder(target).timeout(timeout).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GatewayException("Media download interrupted", e);
            } catch (IOException | IllegalArgumentException e) {
                log.debug("download de mídia falhou: {}", e.toString());
                throw new BadRequestException("Could not download media");
            }
            var status = response.statusCode();
            if (REDIRECTS.contains(status)) {
                close(response);
                var location = response.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    throw new BadRequestException("Could not download media: HTTP " + status);
                }
                if (hop >= MAX_REDIRECTS) {
                    throw new BadRequestException("Could not download media: too many redirects");
                }
                target = parse(resolve(target, location));
                continue;
            }
            if (status / 100 != 2) {
                close(response);
                throw new BadRequestException("Could not download media: HTTP " + status);
            }
            try (var body = response.body()) {
                return body.readNBytes(limit);
            } catch (IOException e) {
                log.debug("leitura da mídia falhou: {}", e.toString());
                throw new BadRequestException("Could not download media");
            }
        }
    }

    private void allow(URI target) {
        check.apply(target.toString()).ifPresent(reason -> {
            throw new BadRequestException("Media url not allowed: " + reason);
        });
    }

    private static URI parse(String url) {
        try {
            var uri = URI.create(url);
            var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new BadRequestException("Media url not allowed: only http(s)");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Media url not allowed: invalid url");
        }
    }

    private static String resolve(URI base, String location) {
        try {
            return base.resolve(location.strip()).toString();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Media url not allowed: invalid redirect");
        }
    }

    private static void close(HttpResponse<java.io.InputStream> response) {
        try {
            response.body().close();
        } catch (IOException ignored) {
            // só liberando a conexão
        }
    }
}
