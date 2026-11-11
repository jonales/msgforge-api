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

    /** Cria o grupo com os participantes (JIDs). A descrição é opcional. */
    GroupInfo createGroup(Instance instance, String subject, String description, java.util.List<String> participants);

    GroupInfo groupInfo(Instance instance, String groupJid);

    /** Grupos de que a conta participa. */
    java.util.List<GroupInfo> groups(Instance instance);

    /** Código do convite (o link é https://chat.whatsapp.com/{código}). */
    String groupInviteCode(Instance instance, String groupJid);

    /** Invalida o convite atual e devolve o novo código. */
    String revokeGroupInvite(Instance instance, String groupJid);

    /** Entra no grupo pelo código do convite; devolve o JID do grupo. */
    String acceptGroupInvite(Instance instance, String inviteCode);

    /** Devolve os JIDs em que a ação deu certo. */
    java.util.List<String> updateParticipants(Instance instance, String groupJid, ParticipantAction action,
                                              java.util.List<String> participants);

    void updateGroup(Instance instance, String groupJid, GroupUpdate update);

    void leaveGroup(Instance instance, String groupJid);

    enum ParticipantAction { ADD, REMOVE, PROMOTE, DEMOTE }

    /** {@code admin}: null, "admin" ou "superadmin" (o criador). */
    record GroupParticipant(String id, String admin) {}

    /**
     * {@code announce}: só admins mandam mensagem. {@code restrict}: só admins editam os dados.
     * Horários em segundos (epoch); 0 quando o WhatsApp não informa.
     */
    record GroupInfo(String id, String subject, String subjectOwner, long subjectTime, long creation,
                     String owner, String desc, boolean announce, boolean restrict,
                     java.util.List<GroupParticipant> participants) {}

    /** Selado: cada motor trata todos os tipos de alteração (switch exaustivo). */
    sealed interface GroupUpdate {}

    record GroupSubject(String subject) implements GroupUpdate {}

    record GroupDescription(String description) implements GroupUpdate {}

    /** {@code image}: URL http(s) ou base64 de um JPEG ou PNG. */
    record GroupPicture(String image) implements GroupUpdate {}

    /** {@code adminsOnly}: true restringe aos admins. */
    record GroupSetting(Setting setting, boolean adminsOnly) implements GroupUpdate {}

    enum Setting { SEND_MESSAGES, EDIT_INFO }

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
