package dev.msgforge.event;

import dev.msgforge.gateway.WhatsAppGateway.SentMessage;
import dev.msgforge.instance.ConnectionState;
import java.util.Map;

/**
 * Eventos emitidos pelo gateway. Hierarquia selada: o dispatcher usa
 * pattern matching exaustivo, então um evento novo sem tratamento não compila.
 */
public sealed interface GatewayEvent {

    String instanceName();

    /** @param base64 imagem PNG do QR como data URL ({@code data:image/png;base64,...}), como na Evolution */
    record QrCodeUpdated(String instanceName, String code, String pairingCode, String base64) implements GatewayEvent {

        public QrCodeUpdated(String instanceName, String code, String pairingCode) {
            this(instanceName, code, pairingCode, null);
        }
    }

    /**
     * @param statusReason código no padrão do Baileys/Evolution (200 ok, 401 logout, 403 banido, 428 conexão fechada)
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

    /** Mensagem recebida, já convertida para o formato do MESSAGES_UPSERT da Evolution. */
    record MessageReceived(String instanceName, Map<String, Object> data) implements GatewayEvent {}

    /** Mudança de status (entregue, lida...), já no formato do MESSAGES_UPDATE da Evolution. */
    record MessageStatusUpdated(String instanceName, Map<String, Object> data) implements GatewayEvent {}
}
