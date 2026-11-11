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

    /** Quais JIDs têm WhatsApp. Mantém a ordem da consulta. */
    java.util.Map<String, Boolean> checkNumbers(Instance instance, java.util.List<String> jids);

    /** Marca mensagens recebidas como lidas (tique azul). */
    void markRead(Instance instance, java.util.List<MessageKey> keys);

    /** Mostra a presença na conversa; com {@code delayMs}, mantém por esse tempo e volta ao normal. */
    void sendPresence(Instance instance, String jid, Presence presence, Integer delayMs);

    /** Perfil de um contato (ou da própria conta, com o JID dela). */
    Profile fetchProfile(Instance instance, String jid);

    /** URL da foto de perfil, ou null se não houver (ou se a privacidade esconder). */
    String profilePictureUrl(Instance instance, String jid);

    /** Altera o perfil da própria conta. */
    void updateProfile(Instance instance, ProfileUpdate update);

    record MessageKey(String remoteJid, String id, boolean fromMe) {}

    enum Presence { AVAILABLE, UNAVAILABLE, COMPOSING, RECORDING, PAUSED }

    record Profile(String jid, boolean exists, String name, String pictureUrl, String status) {}

    /** Selado: cada motor trata todos os tipos de alteração (switch exaustivo). */
    sealed interface ProfileUpdate {}

    record ProfileName(String name) implements ProfileUpdate {}

    record ProfileStatus(String status) implements ProfileUpdate {}

    /** {@code picture}: URL http(s) ou base64, como em OutgoingMedia. */
    record ProfilePicture(String picture) implements ProfileUpdate {}

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

    /**
     * {@code audio}: URL http(s) ou base64, como em OutgoingMedia. Enviado como mensagem de voz (PTT).
     * {@code encode}: converter para OGG/Opus com o ffmpeg antes de enviar.
     */
    record VoiceNote(String jid, String audio, Integer delayMs, boolean encode) implements Outgoing {}

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
