package dev.msgforge.gateway.cobalt;

import it.auties.whatsapp.model.node.Node;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cobre duas lacunas do Cobalt 0.0.10 na fila offline do WhatsApp. Sem elas, a instância loga,
 * envia, mas deixa de receber: o servidor segura as mensagens novas até a fila offline esvaziar.
 *
 * <ol>
 *   <li><b>offline_batch.</b> Ao conectar, o servidor manda um {@code ib/offline_preview} com o
 *       tamanho da fila, entrega só um primeiro lote e espera o cliente pedir o resto com
 *       {@code ib/offline_batch}. O Cobalt não implementa esse pedido.</li>
 *   <li><b>ack que falta.</b> O decoder do Cobalt quebra em algumas mensagens de sincronização do
 *       celular ({@code NullPointerException: protocolType/title cannot be null}) e não manda o
 *       ack; o servidor reenvia a mensagem a cada login. Se o Cobalt não confirmar uma mensagem
 *       em {@code delayMs}, o ack sai daqui. A mensagem quebrada se perde de qualquer jeito.</li>
 * </ol>
 */
final class OfflineQueueGuard {

    private static final Logger log = LoggerFactory.getLogger(OfflineQueueGuard.class);
    /** Mensagens offline pedidas por lote (o mesmo valor dos clientes oficiais). */
    static final int OFFLINE_BATCH_SIZE = 100;

    private final String instanceName;
    private final ScheduledExecutorService scheduler;
    private final long delayMs;
    private final Consumer<Node> sender;
    /** id da mensagem → atributos para montar o ack. */
    private final Map<String, Map<String, Object>> pending = new ConcurrentHashMap<>();

    OfflineQueueGuard(String instanceName, ScheduledExecutorService scheduler, long delayMs, Consumer<Node> sender) {
        this.instanceName = instanceName;
        this.scheduler = scheduler;
        this.delayMs = delayMs;
        this.sender = sender;
    }

    /** Nó recebido do servidor. */
    void received(Node node) {
        if (node.hasDescription("ib")) {
            if (node.findChild("offline_preview").isPresent()) {
                requestOfflineBatch();
            }
            return;
        }
        if (!node.hasDescription("message")) {
            return;
        }
        var attributes = node.attributes().toMap();
        var id = attributes.get("id");
        var from = attributes.get("from");
        if (id == null || from == null) {
            return;
        }
        var ack = new LinkedHashMap<String, Object>();
        ack.put("id", id);
        ack.put("to", from);
        ack.put("class", "message");
        if (attributes.get("type") != null) {
            ack.put("type", attributes.get("type"));
        }
        if (attributes.get("participant") != null) {
            ack.put("participant", attributes.get("participant"));
        }
        pending.put(id.toString(), ack);
        scheduler.schedule(() -> check(id.toString()), delayMs, TimeUnit.MILLISECONDS);
    }

    /** Nó enviado pelo Cobalt: o ack de uma mensagem a tira da lista. */
    void sent(Node node) {
        if (!node.hasDescription("ack")) {
            return;
        }
        var attributes = node.attributes().toMap();
        if ("message".equals(String.valueOf(attributes.get("class"))) && attributes.get("id") != null) {
            pending.remove(attributes.get("id").toString());
        }
    }

    int pendingCount() {
        return pending.size();
    }

    private void requestOfflineBatch() {
        log.info("[cobalt] {}: pedindo a fila offline (offline_batch {})", instanceName, OFFLINE_BATCH_SIZE);
        send(Node.of("ib", Map.of(), Node.of("offline_batch", Map.of("count", OFFLINE_BATCH_SIZE), new Node[0])),
                "offline_batch");
    }

    private void check(String id) {
        var ack = pending.remove(id);
        if (ack == null) {
            return;
        }
        log.warn("[cobalt] {}: mensagem {} de {} ficou sem ack (o Cobalt não a decodificou); "
                + "ack enviado para não travar a fila", instanceName, id, ack.get("to"));
        // new Node[0]: sem ele, cai em of(String, Object), que trata o map como conteúdo
        send(Node.of("ack", ack, new Node[0]), "ack de " + id);
    }

    private void send(Node node, String what) {
        try {
            sender.accept(node);
        } catch (RuntimeException e) {
            log.warn("[cobalt] {}: falha ao enviar {}: {}", instanceName, what, e.toString());
        }
    }
}
