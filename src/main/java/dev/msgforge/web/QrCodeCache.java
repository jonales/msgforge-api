package dev.msgforge.web;

import dev.msgforge.event.GatewayEvent;
import dev.msgforge.event.GatewayEvent.ConnectionUpdated;
import dev.msgforge.event.GatewayEvent.QrCodeUpdated;
import dev.msgforge.instance.ConnectionState;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Último QR / pairing code de cada instância, para a tela "conectar" do Manager se atualizar
 * sozinha (o WhatsApp renova o QR a cada ~20 s). Só em memória: some quando a instância abre,
 * fecha, ou quando a aplicação reinicia. Nunca é enviado para a API.
 */
@Component
public class QrCodeCache {

    public record Entry(String code, String pairingCode, String base64, Instant updatedAt) {}

    private final ConcurrentHashMap<String, Entry> latest = new ConcurrentHashMap<>();

    @EventListener
    public void on(GatewayEvent event) {
        switch (event) {
            case QrCodeUpdated q -> latest.put(q.instanceName(),
                    new Entry(q.code(), q.pairingCode(), q.base64(), Instant.now()));
            case ConnectionUpdated c when c.state() != ConnectionState.CONNECTING -> latest.remove(c.instanceName());
            default -> { }
        }
    }

    public Optional<Entry> get(String instanceName) {
        return Optional.ofNullable(latest.get(instanceName));
    }

    /** Grava o código devolvido diretamente pelo connect; ignora se não houver código. */
    public void remember(String instanceName, String code, String pairingCode, String base64) {
        if (code != null || pairingCode != null) {
            latest.put(instanceName, new Entry(code, pairingCode, base64, Instant.now()));
        }
    }

    public void forget(String instanceName) {
        latest.remove(instanceName);
    }
}
