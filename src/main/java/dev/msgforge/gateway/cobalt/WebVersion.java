package dev.msgforge.gateway.cobalt;

import it.auties.whatsapp.model.signal.auth.Version;
import it.auties.whatsapp.util.AppMetadata;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Versão do WhatsApp Web anunciada no login.
 *
 * <p>O Cobalt 0.0.10 procura {@code client_revision} na página de {@code web.whatsapp.com}, que não
 * traz mais esse valor (e responde 400 aos headers de navegador que ele manda). Ele então usa uma
 * versão fixa antiga, e o servidor passa a recusar o login com {@code failure 405}. A revisão atual
 * está no {@code sw.js}. Daqui ela vai para o cache do Cobalt ({@code AppMetadata.webVersion}),
 * por reflexão, antes de cada conexão.
 *
 * <p>{@code COBALT_WEB_VERSION} fixa a versão (saída de emergência). A busca passa pelo
 * {@code COBALT_PROXY}, como o resto do tráfego para o WhatsApp; com proxy SOCKS, não há busca.
 */
final class WebVersion {

    private static final Logger log = LoggerFactory.getLogger(WebVersion.class);
    static final URI SERVICE_WORKER = URI.create("https://web.whatsapp.com/sw.js");
    /** No sw.js o JSON vem escapado dentro de uma string: {@code \"client_revision\":1054172749}. */
    private static final Pattern REVISION = Pattern.compile("client_revision\\\\?\"\\s*:\\s*(\\d+)");
    static final Duration REFRESH = Duration.ofHours(6);
    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(5);

    private final String override;
    private final HttpClient http;
    private final Clock clock;
    private Instant nextRefresh = Instant.MIN;

    WebVersion(String override, String proxy, Clock clock) {
        this.override = override == null || override.isBlank() ? null : override.trim();
        this.http = client(proxy);
        this.clock = clock;
    }

    /** Garante que o Cobalt use a versão atual. Chamado antes de cada conexão; busca no máximo a cada 6 h. */
    synchronized void apply() {
        var now = clock.instant();
        if (now.isBefore(nextRefresh)) {
            return;
        }
        var version = override != null ? Optional.of(override) : fetch();
        nextRefresh = now.plus(version.isPresent() ? REFRESH : RETRY_AFTER_FAILURE);
        version.ifPresent(WebVersion::install);
    }

    /** "2.3000.&lt;revisão&gt;" a partir do conteúdo do sw.js. */
    static Optional<String> parse(String serviceWorker) {
        var matcher = REVISION.matcher(serviceWorker);
        return matcher.find() ? Optional.of("2.3000." + matcher.group(1)) : Optional.empty();
    }

    private Optional<String> fetch() {
        if (http == null) {
            return Optional.empty();
        }
        try {
            var request = HttpRequest.newBuilder(SERVICE_WORKER).timeout(Duration.ofSeconds(5)).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            var version = response.statusCode() == 200 ? parse(response.body()) : Optional.<String>empty();
            if (version.isEmpty()) {
                log.warn("[cobalt] versão do WhatsApp Web não encontrada no sw.js (HTTP {}); o Cobalt usará a dele",
                        response.statusCode());
            }
            return version;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[cobalt] falha ao buscar a versão do WhatsApp Web: {}; o Cobalt usará a dele", e.toString());
            return Optional.empty();
        }
    }

    private static void install(String version) {
        try {
            var field = AppMetadata.class.getDeclaredField("webVersion");
            field.setAccessible(true);
            var parsed = Version.of(version);
            if (!parsed.equals(field.get(null))) {
                field.set(null, parsed);
                log.info("[cobalt] versão do WhatsApp Web: {}", version);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warn("[cobalt] não foi possível ajustar a versão do WhatsApp Web ({}): {}", version, e.toString());
        }
    }

    private static HttpClient client(String proxy) {
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (proxy != null && !proxy.isBlank()) {
            var uri = URI.create(proxy);
            if (uri.getScheme() == null || !uri.getScheme().startsWith("http")) {
                return null;   // SOCKS: o HttpClient do JDK não suporta
            }
            var port = uri.getPort() != -1 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
            builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), port)));
        }
        return builder.build();
    }
}
