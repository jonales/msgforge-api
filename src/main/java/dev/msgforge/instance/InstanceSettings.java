package dev.msgforge.instance;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Configurações da instância vindas do {@code POST /instance/create} (nomes do contrato). O motor
 * as lê no connect; as regras ficam aqui, sem nada do motor, para valerem igual em qualquer gateway.
 *
 * @param rejectCall   recusa as chamadas recebidas
 * @param msgCall      texto enviado a quem ligou (vazio = nenhum)
 * @param groupsIgnore mensagens de grupo não viram {@code messages.upsert}
 * @param alwaysOnline aparecer sempre "online" (hoje sem efeito: ver CLAUDE.md, problemas conhecidos)
 * @param readMessages marca como lidas as mensagens recebidas
 * @param readStatus   recebe e marca como vistos os status; false = status não viram {@code messages.upsert}
 */
@Embeddable
public record InstanceSettings(
        @Column(name = "reject_call", nullable = false) boolean rejectCall,
        @Column(name = "msg_call", length = 4096) String msgCall,
        @Column(name = "groups_ignore", nullable = false) boolean groupsIgnore,
        @Column(name = "always_online", nullable = false) boolean alwaysOnline,
        @Column(name = "read_messages", nullable = false) boolean readMessages,
        @Column(name = "read_status", nullable = false) boolean readStatus) {

    public static final int MAX_MSG_CALL_LENGTH = 4096;

    /** Os padrões do contrato: tudo desligado. */
    public static final InstanceSettings DEFAULTS = new InstanceSettings(false, null, false, false, false, false);

    public static final String STATUS_BROADCAST = "status@broadcast";

    /** Uma mensagem recebida nesta conversa vira {@code messages.upsert}? */
    public boolean delivers(String chatJid) {
        if (chatJid == null) {
            return true;
        }
        if (STATUS_BROADCAST.equals(chatJid)) {
            return readStatus;
        }
        return !(groupsIgnore && chatJid.endsWith("@g.us"));
    }

    /** Marcar como lida, ao chegar, uma mensagem desta conversa? */
    public boolean marksAsRead(String chatJid, boolean fromMe) {
        if (fromMe || !delivers(chatJid)) {
            return false;
        }
        return STATUS_BROADCAST.equals(chatJid) ? readStatus : readMessages;
    }

    /** Texto para quem ligou, ou null se não há. */
    public String callReply() {
        return msgCall == null || msgCall.isBlank() ? null : msgCall;
    }
}
