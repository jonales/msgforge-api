package dev.msgforge.gateway;

import dev.msgforge.instance.Instance;

/**
 * Fronteira entre a API e o motor do protocolo WhatsApp.
 * Hoje existe o {@link StubWhatsAppGateway} (desenvolvimento); a Fase 2 adiciona
 * a implementação com Cobalt. Nada fora deste pacote deve depender do motor.
 */
public interface WhatsAppGateway {

    /** Inicia (ou retoma) a sessão. Retorna o QR / pairing code quando houver. */
    ConnectResult connect(Instance instance, String phoneNumberForPairing);

    void logout(Instance instance);

    SentMessage sendText(Instance instance, OutgoingText text);

    SentMessage sendMedia(Instance instance, OutgoingMedia media);

    record ConnectResult(String pairingCode, String qrCode, String qrBase64) {}

    record OutgoingText(String jid, String text, Integer delayMs, Boolean linkPreview) {}

    record OutgoingMedia(String jid, String mediaType, String mimeType, String media,
                         String caption, String fileName, Integer delayMs) {}

    record SentMessage(String id, String remoteJid, long timestamp) {}

    /** Falha do motor ao executar a operação (rede, recusa do WhatsApp, timeout). Vira HTTP 500 com a mensagem. */
    class GatewayException extends RuntimeException {
        public GatewayException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
