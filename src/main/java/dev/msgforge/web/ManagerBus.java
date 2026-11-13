package dev.msgforge.web;

import java.util.function.Consumer;

/**
 * Barramento do tempo real do Manager (ADR 0013): o único ponto entre a origem das mudanças
 * ({@link ManagerEventBridge}) e quem as leva às telas ({@link ManagerStreams}).
 *
 * <p>Hoje, com um nó só, a entrega é na memória ({@link LocalManagerBus}). Com mais de um nó, uma
 * implementação sobre o Redis (pub/sub) publica no canal e entrega aos assinantes locais de cada
 * nó; as telas e os streams não mudam.
 */
public interface ManagerBus {

    void publish(ManagerUpdate update);

    void subscribe(Consumer<ManagerUpdate> subscriber);
}
