package dev.msgforge.event;

import dev.msgforge.gateway.WhatsAppGateway.GroupInfo;
import dev.msgforge.gateway.WhatsAppGateway.SentMessage;
import dev.msgforge.instance.ConnectionState;
import java.util.List;
import java.util.Map;

/**
 * Eventos emitidos pelo gateway. Hierarquia selada: o dispatcher usa
 * pattern matching exaustivo, então um evento novo sem tratamento não compila.
 *
 * <p>Os eventos trazem dados neutros (sem classes do motor); o formato do {@code data} do contrato
 * é montado em {@link EventPayloads}. JIDs já vêm no formato do contrato (LID traduzido quando o
 * motor conhece o número).
 */
public sealed interface GatewayEvent {

    /** Instância de origem; null só no {@link ApplicationStartup}, que não pertence a nenhuma. */
    String instanceName();

    /** A aplicação subiu (APPLICATION_STARTUP): vai só para o webhook global e o RabbitMQ. */
    record ApplicationStartup() implements GatewayEvent {
        @Override
        public String instanceName() {
            return null;
        }
    }

    /** @param base64 imagem PNG do QR como data URL ({@code data:image/png;base64,...}) */
    record QrCodeUpdated(String instanceName, String code, String pairingCode, String base64) implements GatewayEvent {

        public QrCodeUpdated(String instanceName, String code, String pairingCode) {
            this(instanceName, code, pairingCode, null);
        }
    }

    /**
     * @param statusReason código no padrão do Baileys (200 ok, 401 logout, 403 banido, 428 conexão fechada)
     * @param wuid         JID do número conectado; só quando {@code state} é OPEN
     * @param profileName  nome do perfil conectado; só quando {@code state} é OPEN
     */
    record ConnectionUpdated(String instanceName, ConnectionState state, Integer statusReason,
                             String wuid, String profileName) implements GatewayEvent {

        public ConnectionUpdated(String instanceName, ConnectionState state, Integer statusReason) {
            this(instanceName, state, statusReason, null, null);
        }
    }

    record MessageSent(String instanceName, SentMessage message) implements GatewayEvent {}

    /** Mensagem recebida, já convertida para o formato do MESSAGES_UPSERT do contrato. */
    record MessageReceived(String instanceName, Map<String, Object> data) implements GatewayEvent {}

    /** Mudança de status (entregue, lida...), já no formato do MESSAGES_UPDATE do contrato. */
    record MessageStatusUpdated(String instanceName, Map<String, Object> data) implements GatewayEvent {}

    /** Lote do histórico sincronizado no pareamento (MESSAGES_SET), cada item no formato do MESSAGES_UPSERT. */
    record MessagesSynced(String instanceName, List<Map<String, Object>> messages) implements GatewayEvent {}

    /** Mensagem apagada para todos (MESSAGES_DELETE): a key da mensagem original. */
    record MessageDeleted(String instanceName, String remoteJid, boolean fromMe, String id, String participant)
            implements GatewayEvent {}

    /** @param pushName nome do contato; null quando o WhatsApp não informa */
    record Contact(String jid, String pushName) {}

    /** Contatos novos ou sincronizados (CONTACTS_UPSERT). */
    record ContactsUpserted(String instanceName, List<Contact> contacts) implements GatewayEvent {}

    /** Contatos alterados, por exemplo o nome na agenda (CONTACTS_UPDATE). */
    record ContactsUpdated(String instanceName, List<Contact> contacts) implements GatewayEvent {}

    /**
     * Presença de um contato numa conversa (PRESENCE_UPDATE).
     *
     * @param presence  {@code available}, {@code unavailable}, {@code composing}, {@code recording} ou {@code paused}
     * @param lastSeen  visto por último, em segundos (epoch); null quando escondido ou desconhecido
     */
    record PresenceUpdated(String instanceName, String chatJid, String participantJid, String presence, Long lastSeen)
            implements GatewayEvent {}

    /** @param unreadMessages mensagens não lidas; null quando o motor não informa */
    record Chat(String jid, String name, Integer unreadMessages) {}

    /** Conversas novas ou sincronizadas (CHATS_UPSERT). */
    record ChatsUpserted(String instanceName, List<Chat> chats) implements GatewayEvent {}

    /** Conversas alteradas: arquivada, silenciada, fixada, lida (CHATS_UPDATE). */
    record ChatsUpdated(String instanceName, List<String> jids) implements GatewayEvent {}

    /** Conversas apagadas (CHATS_DELETE). */
    record ChatsDeleted(String instanceName, List<String> jids) implements GatewayEvent {}

    /** Grupos em que a conta entrou ou que criou (GROUPS_UPSERT). */
    record GroupsUpserted(String instanceName, List<GroupInfo> groups) implements GatewayEvent {}

    /**
     * Dados do grupo alterados (GROUP_UPDATE). Só os campos que mudaram vêm preenchidos.
     *
     * @param author quem alterou; null quando o WhatsApp não informa
     */
    record GroupUpdated(String instanceName, String groupJid, String author, String subject, String desc,
                        Boolean announce, Boolean restrict) implements GatewayEvent {}

    /**
     * Participantes adicionados, removidos, promovidos ou rebaixados (GROUP_PARTICIPANTS_UPDATE).
     *
     * @param action {@code add}, {@code remove}, {@code promote}, {@code demote} ou {@code modify}
     */
    record GroupParticipantsUpdated(String instanceName, String groupJid, String author, List<String> participants,
                                    String action) implements GatewayEvent {}

    /**
     * Chamada de voz ou vídeo (CALL).
     *
     * @param status    {@code offer} (tocando), {@code accept}, {@code reject}, {@code timeout} ou {@code terminate}
     * @param timestamp em milissegundos (epoch)
     */
    record CallReceived(String instanceName, String id, String from, String chatId, String groupJid,
                        boolean isVideo, String status, boolean offline, long timestamp) implements GatewayEvent {}
}
