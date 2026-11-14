package dev.msgforge.event;

import java.util.Locale;

/**
 * Eventos do contrato da API (usados em webhook.events). Os nomes são estáveis: não renomeie.
 *
 * <p>Dois nomes do contrato não seguem a regra geral: {@code GROUP_UPDATE} sai como
 * {@code groups.update} e {@code GROUP_PARTICIPANTS_UPDATE} como {@code group-participants.update}
 * (os nomes dos eventos do Baileys).
 */
public enum EventType {
    APPLICATION_STARTUP,
    QRCODE_UPDATED,
    CONNECTION_UPDATE,
    MESSAGES_SET,
    MESSAGES_UPSERT,
    MESSAGES_UPDATE,
    MESSAGES_DELETE,
    SEND_MESSAGE,
    CONTACTS_UPSERT,
    CONTACTS_UPDATE,
    PRESENCE_UPDATE,
    CHATS_UPSERT,
    CHATS_UPDATE,
    CHATS_DELETE,
    GROUPS_UPSERT,
    GROUP_UPDATE,
    GROUP_PARTICIPANTS_UPDATE,
    CALL;

    /** Nome no payload: "messages.upsert". */
    public String payloadName() {
        return switch (this) {
            case GROUP_UPDATE -> "groups.update";
            case GROUP_PARTICIPANTS_UPDATE -> "group-participants.update";
            default -> name().toLowerCase(Locale.ROOT).replace('_', '.');
        };
    }

    /** Sufixo de URL quando webhook_by_events=true: "/messages-upsert" (do nome no payload, trocando "." por "-"). */
    public String urlSuffix() {
        return "/" + payloadName().replace('.', '-');
    }
}
