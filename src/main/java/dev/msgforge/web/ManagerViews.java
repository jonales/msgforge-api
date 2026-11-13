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
                          Instant createdAt, String historySync) {

        static InstanceDetail of(Instance instance, ConnectionState state) {
            return new InstanceDetail(instance.getName(), state.value(), numberOf(instance),
                    instance.getProfileName(), instance.getIntegration(), instance.getCreatedAt(),
                    instance.getHistorySync().name());
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

    // ---- Mensagens (histórico, ADR 0008)

    private static final java.time.format.DateTimeFormatter TIME =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(java.time.ZoneId.systemDefault());

    /** Uma conversa na lista: o contato, a última mensagem e quando ela chegou. */
    public record ChatRow(String jid, String name, String preview, String time, boolean fromMe) {

        /** O nome do contato; sem nome, o telefone (+55...) ou o JID (grupos e outros). */
        public String label() {
            if (name != null && !name.isBlank()) {
                return name;
            }
            return jid.endsWith("@s.whatsapp.net") ? "+" + jid.substring(0, jid.indexOf('@')) : jid;
        }
    }

    /** Uma mensagem da conversa aberta. {@code author}: o nome de quem mandou (nas recebidas). */
    public record MessageRow(String id, boolean fromMe, String author, String preview, String time, String status) {}

    /** Página da conversa, da mais antiga para a mais nova; {@code page} 1 é a mais recente. */
    public record Conversation(String jid, List<MessageRow> rows, int page, long pages) {

        public boolean hasOlder() {
            return page < pages;
        }

        public int olderPage() {
            return page + 1;
        }
    }

    public static String time(long epochSeconds) {
        return TIME.format(Instant.ofEpochSecond(epochSeconds));
    }

    /** Texto curto para a tela: o texto da mensagem, a legenda da mídia ou o tipo entre colchetes. */
    public static String preview(String type, java.util.Map<String, Object> message) {
        var content = message == null ? null : message.get(type);
        if (content instanceof String text) {
            return text;   // conversation
        }
        if (content instanceof java.util.Map<?, ?> fields) {
            for (var key : List.of("text", "caption", "name", "displayName", "fileName")) {
                if (fields.get(key) instanceof String value && !value.isBlank()) {
                    return "[" + label(type) + "] " + value;
                }
            }
        }
        return "[" + label(type) + "]";
    }

    private static String label(String type) {
        return type == null ? "?" : type.endsWith("Message") ? type.substring(0, type.length() - "Message".length()) : type;
    }
}
