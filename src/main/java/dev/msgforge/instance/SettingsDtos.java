package dev.msgforge.instance;

import jakarta.validation.constraints.NotNull;

/** DTOs de /settings e /proxy no formato JSON do contrato da API (docs/COMPATIBILIDADE.md). */
public final class SettingsDtos {

    private SettingsDtos() {}

    /**
     * Corpo do {@code POST /settings/set}. Como no contrato, os seis booleanos são obrigatórios e
     * {@code msgCall} é opcional. Campos desconhecidos (como {@code wavoipToken}) são ignorados.
     */
    public record SettingsRequest(
            @NotNull Boolean rejectCall,
            String msgCall,
            @NotNull Boolean groupsIgnore,
            @NotNull Boolean alwaysOnline,
            @NotNull Boolean readMessages,
            @NotNull Boolean readStatus,
            @NotNull Boolean syncFullHistory) {}

    /** As configurações como o {@code /settings/find} as devolve. */
    public record Settings(boolean rejectCall, String msgCall, boolean groupsIgnore, boolean alwaysOnline,
                           boolean readMessages, boolean readStatus, boolean syncFullHistory) {

        static Settings of(Instance instance) {
            var s = instance.getSettings();
            return new Settings(s.rejectCall(), s.msgCall() == null ? "" : s.msgCall(), s.groupsIgnore(),
                    s.alwaysOnline(), s.readMessages(), s.readStatus(),
                    instance.getHistorySync() == HistorySync.FULL);
        }
    }

    /** Resposta do {@code POST /settings/set}: {@code {settings: {instanceName, settings: {...}}}}. */
    public record SetSettingsResponse(Body settings) {
        public record Body(String instanceName, Settings settings) {}
    }

    /** Corpo do {@code POST /proxy/set}. {@code port} aceita texto ou número, como no create. */
    public record ProxyRequest(
            @NotNull Boolean enabled,
            String host,
            String port,
            String protocol,
            String username,
            String password) {

        /** A senha nunca vai para log nem mensagem de erro. */
        @Override
        public String toString() {
            return "ProxyRequest[enabled=" + enabled + ", " + protocol + "://" + host + ":" + port
                    + ", username=" + username + ", password=" + (password == null ? null : "***") + "]";
        }
    }

    /** O proxy como o {@code /proxy/find} e o {@code /proxy/set} o devolvem. A senha nunca sai. */
    public record Proxy(boolean enabled, String host, String port, String protocol, String username) {

        static final Proxy DISABLED = new Proxy(false, "", "", "", "");

        static Proxy of(Instance instance) {
            return instance.getProxy()
                    .map(p -> new Proxy(true, p.host(), String.valueOf(p.port()), p.protocol(),
                            p.username() == null ? "" : p.username()))
                    .orElse(DISABLED);
        }
    }

    /** Resposta do {@code POST /proxy/set}: {@code {proxy: {instanceName, proxy: {...}}}}, sem a senha. */
    public record SetProxyResponse(Body proxy) {
        public record Body(String instanceName, Proxy proxy) {}
    }
}
