package dev.msgforge.gateway.cobalt;

import com.github.auties00.cobalt.stanza.model.Stanza;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * {@code messages.update} das mensagens que a instância enviou, a partir dos nós do protocolo.
 *
 * <p>O status sai pelo id da mensagem, que nós mesmos registramos no envio, sem depender de o
 * Cobalt achar a mensagem pelo chat (o recibo chega do LID do destinatário e a mensagem pode estar
 * no chat do número):
 * {@code ack} do servidor → SERVER_ACK; {@code receipt} sem tipo → DELIVERY_ACK; {@code read} →
 * READ; {@code played} → PLAYED. Os outros recibos ({@code sender}, {@code retry},
 * {@code inactive}, {@code peer_msg}...) não são status para o contrato.
 *
 * <p>Cada mensagem só avança (o recibo de cada aparelho do destinatário chega separado). Um status
 * que chegue antes de o envio ser registrado fica guardado e sai no registro.
 */
final class SentStatusTracker {

    static final int MAX_TRACKED = 5_000;
    static final int MAX_EARLY = 500;

    private final UUID instanceId;
    private final Consumer<Map<String, Object>> publish;
    /** id → remoteJid (como foi enviado) */
    private final Map<String, String> remoteJids = bounded(MAX_TRACKED);
    /** id → maior status já publicado */
    private final Map<String, Integer> ranks = bounded(MAX_TRACKED);
    /** id → maior status recebido antes do registro do envio */
    private final Map<String, Integer> early = bounded(MAX_EARLY);

    SentStatusTracker(UUID instanceId, Consumer<Map<String, Object>> publish) {
        this.instanceId = instanceId;
        this.publish = publish;
    }

    /** Envio concluído: a partir daqui os recibos com esse id viram messages.update. */
    void sent(String id, String remoteJid) {
        Integer pendingRank;
        synchronized (this) {
            remoteJids.put(id, remoteJid);
            pendingRank = early.remove(id);
        }
        if (pendingRank != null) {
            advance(id, pendingRank);
        }
    }

    /** Nó recebido do servidor. */
    void received(Stanza node) {
        int rank;
        if (node.hasDescription("ack")) {
            if (!"message".equals(node.getAttributeAsString("class", null)) || node.getAttribute("error").isPresent()) {
                return;
            }
            rank = rank("SERVER_ACK");
        } else if (node.hasDescription("receipt")) {
            rank = switch (node.getAttributeAsString("type", "")) {
                case "" -> rank("DELIVERY_ACK");
                case "read" -> rank("READ");
                case "played" -> rank("PLAYED");
                default -> 0;
            };
        } else {
            return;
        }
        if (rank == 0) {
            return;
        }
        for (var id : ids(node)) {
            advance(id, rank);
        }
    }

    /** Status vindo do listener do próprio Cobalt: true se for novo (e deve ser publicado). */
    boolean advanceFromCobalt(String id, String status) {
        var rank = rank(status);
        synchronized (this) {
            if (rank <= ranks.getOrDefault(id, 0)) {
                return false;
            }
            ranks.put(id, rank);
            return true;
        }
    }

    private void advance(String id, int rank) {
        String remoteJid;
        synchronized (this) {
            remoteJid = remoteJids.get(id);
            if (remoteJid == null) {
                if (rank > early.getOrDefault(id, 0)) {
                    early.put(id, rank);
                }
                return;
            }
            if (rank <= ranks.getOrDefault(id, 0)) {
                return;
            }
            ranks.put(id, rank);
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("messageId", id);
        data.put("keyId", id);
        data.put("remoteJid", remoteJid);
        data.put("fromMe", true);
        data.put("status", STATUSES.get(rank - 1));
        data.put("instanceId", instanceId.toString());
        publish.accept(data);
    }

    private static final List<String> STATUSES = List.of("SERVER_ACK", "DELIVERY_ACK", "READ", "PLAYED");

    static int rank(String status) {
        return STATUSES.indexOf(status) + 1;
    }

    /** O id do nó e, num recibo em lote, os de {@code list/item}. */
    private static List<String> ids(Stanza node) {
        var ids = new ArrayList<String>();
        node.getAttributeAsString("id").ifPresent(ids::add);
        node.getChild("list").ifPresent(list -> list.streamChildren("item")
                .flatMap(item -> item.getAttributeAsString("id").stream())
                .forEach(ids::add));
        return ids;
    }

    private static <V> Map<String, V> bounded(int max) {
        return new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > max;
            }
        };
    }
}
