package dev.msgforge.gateway.cobalt;

import static org.assertj.core.api.Assertions.assertThat;

import it.auties.whatsapp.model.node.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Sequências de nós vistas no teste ao vivo (números fictícios). */
class SentStatusTrackerTest {

    private static final String TO = "5561988887777@s.whatsapp.net";
    private final UUID instanceId = UUID.randomUUID();
    private final List<Map<String, Object>> published = new ArrayList<>();
    private final SentStatusTracker tracker = new SentStatusTracker(instanceId, published::add);

    private static Node node(String description, Map<String, Object> attributes, Node... children) {
        return Node.of(description, attributes, children);
    }

    private List<Object> statuses() {
        return published.stream().map(d -> d.get("status")).toList();
    }

    @Test
    void serverAckDeliveryFromEachDeviceAndRead() {
        tracker.sent("3EB0A", TO);

        tracker.received(node("ack", Map.of("from", TO, "class", "message", "id", "3EB0A")));
        // o próprio aparelho primário confirma o envio: não é status para o contrato
        tracker.received(node("receipt", Map.of("from", "111111111111111_1:15@s.whatsapp.net", "type", "sender", "id", "3EB0A")));
        // cada aparelho do destinatário manda o seu recibo de entrega
        tracker.received(node("receipt", Map.of("from", "222222222222222_1:46@s.whatsapp.net", "id", "3EB0A")));
        tracker.received(node("receipt", Map.of("from", "222222222222222_1@s.whatsapp.net", "id", "3EB0A")));
        tracker.received(node("receipt", Map.of("from", "222222222222222_1@s.whatsapp.net", "type", "read", "id", "3EB0A")));

        assertThat(statuses()).containsExactly("SERVER_ACK", "DELIVERY_ACK", "READ");
        assertThat(published.getLast()).containsEntry("messageId", "3EB0A").containsEntry("keyId", "3EB0A")
                .containsEntry("remoteJid", TO).containsEntry("fromMe", true)
                .containsEntry("instanceId", instanceId.toString());
    }

    @Test
    void ignoresMessagesWeDidNotSendAndNonStatusReceipts() {
        tracker.sent("3EB0A", TO);

        tracker.received(node("receipt", Map.of("from", "222222222222222_1@s.whatsapp.net", "id", "OUTRA")));
        tracker.received(node("receipt", Map.of("from", "222222222222222_1:46@s.whatsapp.net", "type", "retry", "id", "3EB0A")));
        tracker.received(node("receipt", Map.of("from", "111111111111111_1:15@s.whatsapp.net", "type", "inactive", "id", "3EB0A")));
        tracker.received(node("ack", Map.of("class", "receipt", "id", "3EB0A")));
        tracker.received(node("ack", Map.of("class", "message", "id", "3EB0A", "error", "479")));

        assertThat(published).isEmpty();
    }

    @Test
    void statusArrivingBeforeTheSendIsRegisteredIsNotLost() {
        tracker.received(node("ack", Map.of("class", "message", "id", "3EB0A")));
        tracker.received(node("receipt", Map.of("id", "3EB0A")));

        tracker.sent("3EB0A", TO);

        assertThat(statuses()).containsExactly("DELIVERY_ACK");
    }

    @Test
    void batchReadReceiptCoversEveryListedMessage() {
        tracker.sent("3EB0A", TO);
        tracker.sent("3EB0B", TO);

        tracker.received(node("receipt", Map.of("type", "read", "id", "3EB0A"),
                node("list", Map.of(), node("item", Map.of("id", "3EB0B")))));

        assertThat(published).extracting(d -> d.get("messageId")).containsExactly("3EB0A", "3EB0B");
        assertThat(statuses()).containsOnly("READ");
    }

    @Test
    void cobaltListenerDoesNotDuplicateWhatTheNodesAlreadyPublished() {
        tracker.sent("3EB0A", TO);
        tracker.received(node("receipt", Map.of("id", "3EB0A")));

        assertThat(tracker.advanceFromCobalt("3EB0A", "DELIVERY_ACK")).isFalse();
        assertThat(tracker.advanceFromCobalt("3EB0A", "READ")).isTrue();
        tracker.received(node("receipt", Map.of("type", "read", "id", "3EB0A")));

        assertThat(statuses()).containsExactly("DELIVERY_ACK");
    }
}
