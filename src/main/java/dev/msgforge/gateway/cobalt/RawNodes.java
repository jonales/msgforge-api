package dev.msgforge.gateway.cobalt;

import it.auties.whatsapp.api.Whatsapp;
import it.auties.whatsapp.model.node.Node;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Envia um nó do protocolo exatamente como foi montado, sem esperar resposta.
 *
 * <p>O {@code Whatsapp.sendNode} público acrescenta um {@code id} aleatório e espera a resposta;
 * o servidor ignora um {@code ib/offline_batch} com id. O {@code SocketHandler} do Cobalt tem o
 * {@code sendNodeWithNoResponse}, que não mexe no nó, mas não é exposto: o acesso é por reflexão.
 * Se a reflexão falhar (outra versão do Cobalt), cai no {@code sendNode} público.
 */
final class RawNodes {

    private static final Logger log = LoggerFactory.getLogger(RawNodes.class);
    private static final Field SOCKET_HANDLER;
    private static final Method SEND_NO_RESPONSE;

    static {
        Field field = null;
        Method method = null;
        try {
            field = Whatsapp.class.getDeclaredField("socketHandler");
            field.setAccessible(true);
            method = field.getType().getMethod("sendNodeWithNoResponse", Node.class);
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warn("[cobalt] sendNodeWithNoResponse indisponível ({}); usando sendNode", e.toString());
        }
        SOCKET_HANDLER = field;
        SEND_NO_RESPONSE = method;
    }

    private RawNodes() {}

    static void send(Whatsapp api, Node node) {
        if (SEND_NO_RESPONSE != null) {
            try {
                ((CompletableFuture<?>) SEND_NO_RESPONSE.invoke(SOCKET_HANDLER.get(api), node))
                        .exceptionally(e -> null);
                return;
            } catch (ReflectiveOperationException e) {
                log.debug("[cobalt] sendNodeWithNoResponse falhou: {}", e.toString());
            }
        }
        api.sendNode(node).exceptionally(e -> null);
    }
}
