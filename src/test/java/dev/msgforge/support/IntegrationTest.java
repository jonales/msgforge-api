package dev.msgforge.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base dos testes de integração: aplicação inteira numa porta aleatória, Postgres e Redis reais
 * (ver {@link TestInfrastructure}), gateway stub e um {@link WebhookReceiver} local.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "msgforge.authentication.api-key=" + IntegrationTest.API_KEY,
        "msgforge.gateway=stub",
        "msgforge.rabbitmq.enabled=false",
        "msgforge.webhook.global-enabled=false",
        "msgforge.webhook.request-timeout-ms=3000",
        "msgforge.webhook.retry.max-attempts=3",
        "msgforge.webhook.retry.initial-delay-ms=50",
        "msgforge.secrets.encryption-key=" + IntegrationTest.SECRETS_KEY,
})
@ExtendWith(RequiresInfrastructure.class)
public abstract class IntegrationTest {

    public static final String API_KEY = "it-global-key-0123456789abcdefghijklmnop";   // >= 32: sobe fora do perfil dev
    public static final String SECRETS_KEY = "it-secrets-key-0123456789abcdefghijklmnop";

    protected static final WebhookReceiver webhooks = new WebhookReceiver();
    private static final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int port;

    @Autowired
    protected JsonMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        var infra = TestInfrastructure.INSTANCE;
        if (infra == null) {
            return;
        }
        registry.add("spring.datasource.url", infra::jdbcUrl);
        registry.add("spring.datasource.username", infra::user);
        registry.add("spring.datasource.password", infra::password);
        registry.add("spring.data.redis.host", infra::redisHost);
        registry.add("spring.data.redis.port", infra::redisPort);
    }

    @BeforeEach
    void cleanState() {
        jdbc.execute("TRUNCATE instance CASCADE");
        webhooks.clear();
    }

    /** Nome de instância único por teste (o cache de estado no Redis sobrevive ao TRUNCATE). */
    protected static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public record Response(int status, JsonNode body) {}

    protected Response call(String method, String path, String apiKey, String jsonBody) {
        return call(method, path, apiKey, jsonBody, java.util.Map.of());
    }

    protected Response call(String method, String path, String apiKey, String jsonBody,
                            java.util.Map<String, String> headers) {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, jsonBody == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(jsonBody))
                .header("Content-Type", "application/json");
        if (apiKey != null) {
            builder.header("apikey", apiKey);
        }
        headers.forEach(builder::header);
        try {
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            var body = response.body().isBlank() ? null : json.readTree(response.body());
            return new Response(response.statusCode(), body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected Response post(String path, String jsonBody) {
        return call("POST", path, API_KEY, jsonBody);
    }

    protected Response get(String path) {
        return call("GET", path, API_KEY, null);
    }

    protected Response delete(String path) {
        return call("DELETE", path, API_KEY, null);
    }

    protected JsonNode parse(String body) {
        return json.readTree(body);
    }
}
