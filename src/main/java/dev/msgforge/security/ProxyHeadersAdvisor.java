package dev.msgforge.security;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Avisa no log quando a API parece estar atrás de um proxy reverso sem {@code TRUSTED_PROXIES}.
 *
 * <p>Nesse caso todos os clientes chegam com o IP do proxy, e o limitador da chave
 * ({@link ApiKeyAttemptLimiter}) bloqueia todos de uma vez: {@code AUTHENTICATION_MAX_FAILURES}
 * chaves erradas de qualquer um dão 429 a todos os integradores (o 429 vale até com a chave
 * certa, de propósito). Quem resolve é a configuração da implantação; aqui fica o sinal no log:
 * um aviso na primeira requisição vinda de IP privado e outro a cada bloqueio.
 */
@Component
public class ProxyHeadersAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ProxyHeadersAdvisor.class);

    private final boolean trustedProxiesConfigured;
    private final AtomicBoolean warned = new AtomicBoolean();

    public ProxyHeadersAdvisor(@Value("${server.tomcat.remoteip.internal-proxies:}") String trustedProxies) {
        this.trustedProxiesConfigured = trustedProxies != null && !trustedProxies.isBlank();
    }

    /** Chamado a cada requisição da API; depois do primeiro aviso, não faz nada. */
    public void seen(String ip) {
        if (trustedProxiesConfigured || warned.get() || !privateNonLoopback(ip)) {
            return;
        }
        if (warned.compareAndSet(false, true)) {
            log.warn("Requisição da API vinda de IP privado ({}) com TRUSTED_PROXIES vazio. Se a aplicação está "
                    + "atrás de um proxy reverso, todos os clientes dividem esse IP no limite de erros de chave "
                    + "e um só bloqueia todos (docs/configuracao.md, \"Atrás de proxy reverso\").", ip);
        }
    }

    /** O IP acabou de passar do limite de erros de chave. */
    public void blocked(String ip) {
        if (!trustedProxiesConfigured && privateNonLoopback(ip)) {
            log.warn("IP privado {} bloqueado por erros de chave da API (429 por até 15 min). Com TRUSTED_PROXIES "
                    + "vazio, se ele for um proxy reverso, todos os clientes atrás dele ficam bloqueados.", ip);
        } else {
            log.warn("IP {} bloqueado por erros de chave da API (429 por até 15 min)", ip);
        }
    }

    static boolean privateNonLoopback(String ip) {
        if (ip == null) {
            return false;
        }
        try {
            var address = InetAddress.ofLiteral(ip);
            return !address.isLoopbackAddress() && PrivateNetworkGuard.isNonPublic(address);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
