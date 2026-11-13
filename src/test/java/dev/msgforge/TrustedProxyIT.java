package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Com o loopback em TRUSTED_PROXIES (os testes chamam de 127.0.0.1, fazendo o papel do proxy), o
 * limitador conta pelo IP do cliente que o proxy informa no X-Forwarded-For: um atacante não
 * bloqueia os outros clientes atrás do mesmo proxy.
 */
@TestPropertySource(properties = {
        "msgforge.authentication.max-failures=3",
        "TRUSTED_PROXIES=127.0.0.1/32,::1/128",
})
class TrustedProxyIT extends IntegrationTest {

    private static final Map<String, String> ATTACKER = Map.of("X-Forwarded-For", "203.0.113.7");
    private static final Map<String, String> OTHER_CLIENT = Map.of("X-Forwarded-For", "198.51.100.20");

    @Test
    void forwardedForFromATrustedProxyIsUsedByTheLimiter() {
        for (var i = 0; i < 3; i++) {
            assertThat(call("GET", "/instance/fetchInstances", "chave-errada", null, ATTACKER).status())
                    .isEqualTo(401);
        }
        assertThat(call("GET", "/instance/fetchInstances", API_KEY, null, ATTACKER).status()).isEqualTo(429);

        // Outro cliente atrás do mesmo proxy segue entrando
        assertThat(call("GET", "/instance/fetchInstances", API_KEY, null, OTHER_CLIENT).status()).isEqualTo(200);

        // O proxy acrescenta o IP real à direita: um valor forjado à esquerda não muda o cliente
        var forgedChain = Map.of("X-Forwarded-For", "198.51.100.20, 203.0.113.7");
        assertThat(call("GET", "/instance/fetchInstances", API_KEY, null, forgedChain).status()).isEqualTo(429);
    }
}
