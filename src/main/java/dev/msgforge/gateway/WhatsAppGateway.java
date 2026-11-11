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

    /** Demais tipos de mensagem (áudio de voz, localização, contatos, reação, enquete). */
    SentMessage send(Instance instance, Outgoing message);

    record ConnectResult(String pairingCode, String qrCode, String qrBase64) {}

    record OutgoingText(String jid, String text, Integer delayMs, Boolean linkPreview) {}

    record OutgoingMedia(String jid, String mediaType, String mimeType, String media,
                         String caption, String fileName, Integer delayMs) {}

    record SentMessage(String id, String remoteJid, long timestamp) {}

    /**
     * Mensagem a enviar, já validada e com o destino em JID. Selado: um tipo novo obriga cada
     * motor a tratá-lo (switch exaustivo).
     */
    sealed interface Outgoing {
        String jid();

        Integer delayMs();
    }

    /** {@code audio}: URL http(s) ou base64, como em OutgoingMedia. Enviado como mensagem de voz (PTT). */
    record VoiceNote(String jid, String audio, Integer delayMs) implements Outgoing {}

    record Location(String jid, double latitude, double longitude, String name, String address,
                    Integer delayMs) implements Outgoing {}

    /** {@code vcard}: cartão já montado (VCARD 3.0). */
    record ContactCard(String displayName, String vcard) {}

    record Contacts(String jid, java.util.List<ContactCard> contacts, Integer delayMs) implements Outgoing {}

    /** Reage à mensagem {@code messageId} da conversa {@code jid}; emoji vazio remove a reação. */
    record Reaction(String jid, String messageId, boolean fromMe, String emoji) implements Outgoing {
        @Override
        public Integer delayMs() {
            return null;
        }
    }

    /** {@code selectableCount}: 1 = escolha única; 0 = várias. */
    record Poll(String jid, String name, int selectableCount, java.util.List<String> values,
                Integer delayMs) implements Outgoing {}

    /** Falha do motor ao executar a operação (rede, recusa do WhatsApp, timeout). Vira HTTP 500 com a mensagem. */
    class GatewayException extends RuntimeException {
        public GatewayException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
