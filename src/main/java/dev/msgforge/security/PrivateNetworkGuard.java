package dev.msgforge.security;

import dev.msgforge.config.MsgforgeProperties;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Proteção opcional contra SSRF nos webhooks de instância ({@code WEBHOOK_BLOCK_PRIVATE_NETWORKS}).
 *
 * <p>Desligada por padrão: é comum o webhook apontar para um serviço na mesma
 * rede Docker (n8n, por exemplo). Ligue quando terceiros tiverem tokens de instância.
 *
 * <p>Limite conhecido: a checagem resolve o DNS antes da conexão, então um DNS que muda de
 * resposta entre a checagem e o connect (DNS rebinding) ainda passa. Com a proteção ligada, o
 * cliente HTTP não segue redirects, para que um endereço público não redirecione para um interno.
 */
@Component
public class PrivateNetworkGuard {

    private final boolean enabled;

    @Autowired
    public PrivateNetworkGuard(MsgforgeProperties properties) {
        this(properties.webhook().blockPrivateNetworks());
    }

    public PrivateNetworkGuard(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** @return o motivo da recusa, ou vazio se a URL pode ser chamada */
    public Optional<String> check(String url) {
        if (!enabled) {
            return Optional.empty();
        }
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return Optional.of("invalid url");
        }
        if (host == null || host.isBlank()) {
            return Optional.of("url without host");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host.startsWith("[") ? host.substring(1, host.length() - 1) : host);
        } catch (UnknownHostException e) {
            return Optional.empty();   // não resolve: a conexão vai falhar de qualquer jeito
        }
        for (var address : addresses) {
            if (isNonPublic(address)) {
                return Optional.of(host + " resolves to a non-public address (" + address.getHostAddress() + ")");
            }
        }
        return Optional.empty();
    }

    static boolean isNonPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        var bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0                                   // 0.0.0.0/8
                    || (first == 100 && second >= 64 && second <= 127)   // 100.64.0.0/10 (CGNAT)
                    || (first == 198 && (second == 18 || second == 19)); // 198.18.0.0/15 (benchmark)
        }
        if (address instanceof Inet6Address) {
            return (bytes[0] & 0xfe) == 0xfc;                   // fc00::/7 (unique local)
        }
        return false;
    }
}
