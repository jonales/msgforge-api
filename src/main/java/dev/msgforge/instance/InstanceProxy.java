package dev.msgforge.instance;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Proxy da conexão desta instância com o WhatsApp (substitui o {@code COBALT_PROXY} global). A senha
 * fica cifrada ({@code security/SecretCipher}, ADR 0014) e nunca aparece no {@link #toString()}.
 */
@Embeddable
public record InstanceProxy(
        @Column(name = "proxy_protocol", length = 10) String protocol,
        @Column(name = "proxy_host", length = 253) String host,
        @Column(name = "proxy_port") Integer port,
        @Column(name = "proxy_username") String username,
        @Column(name = "proxy_password_encrypted", length = 1024) String passwordEncrypted) {

    /** Protocolos que o motor sabe atravessar. {@code socks} é aceito como sinônimo de {@code socks5}. */
    public static final Set<String> PROTOCOLS = Set.of("http", "https", "socks4", "socks4a", "socks5", "socks5h");

    /** Nome, IPv4 ou IPv6 (com ou sem colchetes); nada de usuário, caminho ou espaço. */
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9._:\\[\\]-]{1,253}$");

    public boolean hasPassword() {
        return passwordEncrypted != null;
    }

    /** Protocolo do contrato normalizado, ou null se não é suportado. */
    public static String normalizeProtocol(String protocol) {
        var value = protocol.trim().toLowerCase(Locale.ROOT);
        if (value.equals("socks")) {
            value = "socks5";
        }
        return PROTOCOLS.contains(value) ? value : null;
    }

    public static boolean validHost(String host) {
        return HOST.matcher(host).matches();
    }

    /** Porta do contrato (texto), ou null se não é uma porta TCP. */
    public static Integer parsePort(String port) {
        try {
            var value = Integer.parseInt(port.trim());
            return value >= 1 && value <= 65535 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "InstanceProxy[" + protocol + "://" + (username == null ? "" : username + ":***@")
                + host + ":" + port + "]";
    }
}
