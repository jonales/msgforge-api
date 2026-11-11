package dev.msgforge.event;

import java.util.Locale;

/** Mesmos nomes de eventos da Evolution API (usados em webhook.events). */
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
        return name().toLowerCase(Locale.ROOT).replace('_', '.');
    }

    /** Sufixo de URL quando webhook_by_events=true: "/messages-upsert". */
    public String urlSuffix() {
        return "/" + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
