package dev.msgforge.web;

import dev.msgforge.instance.ConnectionState;
import dev.msgforge.instance.Instance;
import dev.msgforge.webhook.WebhookDtos.WebhookResponse;
import java.time.Instant;
import java.util.List;

/**
 * Modelos das telas do Manager. São próprios da camada web: os DTOs da API são o contrato público e
 * não são reaproveitados aqui (ADR 0005). Nenhum deles carrega o token da instância.
 */
public final class ManagerViews {

    private ManagerViews() {}

    public record InstanceRow(String name, String state, String number, String profileName, Instant createdAt) {

        static InstanceRow of(Instance instance, ConnectionState state) {
            return new InstanceRow(instance.getName(), state.value(), numberOf(instance), instance.getProfileName(),
                    instance.getCreatedAt());
        }
    }

    public record InstanceDetail(String name, String state, String number, String profileName, String integration,
                          Instant createdAt) {

        static InstanceDetail of(Instance instance, ConnectionState state) {
            return new InstanceDetail(instance.getName(), state.value(), numberOf(instance),
                    instance.getProfileName(), instance.getIntegration(), instance.getCreatedAt());
        }

        public boolean open() {
            return "open".equals(state);
        }
    }

    /** Estado + QR para o trecho da página que se atualiza sozinho. */
    public record Live(String name, String state, String qrCode, String qrImage, String pairingCode) {

        public boolean open() {
            return "open".equals(state);
        }

        public boolean hasCode() {
            return qrCode != null || pairingCode != null;
        }
    }

    public record WebhookView(String url, boolean enabled, boolean byEvents, List<String> events) {

        static final WebhookView EMPTY = new WebhookView("", true, false, List.of());

        static WebhookView of(WebhookResponse webhook) {
            return new WebhookView(webhook.url(), Boolean.TRUE.equals(webhook.enabled()),
                    Boolean.TRUE.equals(webhook.webhookByEvents()), webhook.events());
        }
    }

    /** "5561999999999@s.whatsapp.net" → "5561999999999"; sem número conectado, o informado na criação. */
    private static String numberOf(Instance instance) {
        var jid = instance.getOwnerJid();
        if (jid != null && jid.contains("@")) {
            return jid.substring(0, jid.indexOf('@'));
        }
        return instance.getNumber();
    }
}
