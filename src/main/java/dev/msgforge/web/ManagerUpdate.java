package dev.msgforge.web;

/**
 * O que mudou numa instância, do ponto de vista das telas do Manager (ADR 0013). Só texto simples:
 * é o que atravessa o {@link ManagerBus}, inclusive entre nós quando ele passar pelo Redis.
 *
 * @param chatJid a conversa afetada, só em {@link Change#MESSAGES}
 */
public record ManagerUpdate(String instanceName, Change change, String chatJid) {

    public enum Change {
        /** Estado da conexão (lista de instâncias e painel da instância). */
        CONNECTION,
        /** QR ou pairing code novo (painel da instância). */
        QR,
        /** Mensagem recebida, enviada ou com status novo (conversas). */
        MESSAGES
    }
}
