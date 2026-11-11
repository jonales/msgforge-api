package dev.jarzap.event;

import dev.jarzap.gateway.WhatsAppGateway.SentMessage;
import dev.jarzap.instance.ConnectionState;
import java.util.Map;

/**
 * Eventos emitidos pelo gateway. Hierarquia selada: o dispatcher usa
 * pattern matching exaustivo, então um evento novo sem tratamento não compila.
 */
public sealed interface GatewayEvent {

    String instanceName();

    record QrCodeUpdated(String instanceName, String code, String pairingCode) implements GatewayEvent {}

    record ConnectionUpdated(String instanceName, ConnectionState state, Integer statusReason) implements GatewayEvent {}

    record MessageSent(String instanceName, SentMessage message) implements GatewayEvent {}

    /** Mensagem recebida, já convertida para o formato do MESSAGES_UPSERT da Evolution. */
    record MessageReceived(String instanceName, Map<String, Object> data) implements GatewayEvent {}
}
