package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Sem TRUSTED_PROXIES (padrão), o X-Forwarded-For é ignorado: quem forja o cabeçalho continua
 * contado pelo IP da conexão e não escapa do limitador. Contexto próprio: o TRUSTED_PROXIES vazio
 * (o padrão, explícito aqui) separa este contexto do ApiKeyLimitIT, senão um herdaria o bloqueio
 * do 127.0.0.1 deixado pelo outro.
 */
@TestPropertySource(properties = {
        "msgforge.authentication.max-failures=3",
        "TRUSTED_PROXIES=",
})
class UntrustedForwardedForIT extends IntegrationTest {

    @Test
    void forwardedForFromAnUntrustedAddressIsIgnored() {
        for (var i = 0; i < 3; i++) {
            var spoofed = Map.of("X-Forwarded-For", "203.0.113." + (10 + i));
            assertThat(call("GET", "/instance/fetchInstances", "chave-errada", null, spoofed).status()).isEqualTo(401);
        }

        // Os três erros contaram para o 127.0.0.1, não para os IPs forjados
        var otherIp = Map.of("X-Forwarded-For", "203.0.113.99");
        assertThat(call("GET", "/instance/fetchInstances", API_KEY, null, otherIp).status()).isEqualTo(429);
        assertThat(get("/instance/fetchInstances").status()).isEqualTo(429);
    }
}
