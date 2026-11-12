package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Força bruta na chave da API: contexto próprio (limite baixo), para o bloqueio do 127.0.0.1 não
 * vazar para os outros testes de integração.
 */
@TestPropertySource(properties = "msgforge.authentication.max-failures=3")
class ApiKeyLimitIT extends IntegrationTest {

    @Test
    void wrongKeysFromTheSameAddressEndInTooManyRequests() {
        assertThat(get("/instance/fetchInstances").status()).isEqualTo(200);   // chave certa não conta
        for (var i = 0; i < 3; i++) {
            assertThat(call("GET", "/instance/fetchInstances", "chave-errada-" + i, null).status()).isEqualTo(401);
        }

        var blocked = call("GET", "/instance/fetchInstances", "chave-errada-3", null);
        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.body().get("response").get("message").isArray()).isTrue();

        // Bloqueado, nem a chave certa entra (senão o 200 entregaria a chave)
        assertThat(get("/instance/fetchInstances").status()).isEqualTo(429);
        // Rotas públicas continuam abertas
        assertThat(call("GET", "/actuator/health", null, null).status()).isEqualTo(200);
    }
}
