package dev.msgforge.gateway.cobalt;

import static org.assertj.core.api.Assertions.assertThat;

import it.auties.whatsapp.model.node.Node;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OfflineQueueGuardTest {

    private final List<Node> sent = new CopyOnWriteArrayList<>();
    private final ScheduledProbe scheduler = new ScheduledProbe();
    private final OfflineQueueGuard guard = new OfflineQueueGuard("teste", scheduler.executor, 50, sent::add);

    @AfterEach
    void stop() {
        scheduler.executor.shutdownNow();
    }

    private static Node message(String id) {
        return Node.of("message", Map.of("id", id, "from", "277412457795677_1@s.whatsapp.net", "type", "text"),
                new Node[0]);
    }

    @Test
    void acksMessageThatCobaltNeverAcked() throws Exception {
        guard.received(message("3AB05B18"));

        scheduler.awaitIdle();

        assertThat(sent).hasSize(1);
        var ack = sent.getFirst();
        assertThat(ack.description()).isEqualTo("ack");
        assertThat(ack.attributes().toMap()).containsEntry("id", "3AB05B18")
                .containsEntry("to", "277412457795677_1@s.whatsapp.net")
                .containsEntry("class", "message")
                .containsEntry("type", "text");
        assertThat(guard.pendingCount()).isZero();
    }

    @Test
    void requestsOfflineBatchWhenServerAnnouncesTheQueue() {
        var preview = Node.of("offline_preview", Map.of("count", "36", "message", "8"), new Node[0]);
        guard.received(Node.of("ib", Map.of("from", "s.whatsapp.net"), preview));
        guard.received(Node.of("ib", Map.of("from", "s.whatsapp.net"),
                Node.of("edge_routing", Map.of(), new Node[0])));

        assertThat(sent).hasSize(1);
        var ib = sent.getFirst();
        assertThat(ib.description()).isEqualTo("ib");
        var batch = ib.findChild("offline_batch").orElseThrow();
        assertThat(batch.attributes().toMap()).containsEntry("count", OfflineQueueGuard.OFFLINE_BATCH_SIZE);
    }

    @Test
    void doesNothingWhenCobaltAckedInTime() throws Exception {
        guard.received(message("3AC583DD"));
        guard.sent(Node.of("ack", Map.of("id", "3AC583DD", "class", "message"), new Node[0]));

        scheduler.awaitIdle();

        assertThat(sent).isEmpty();
    }

    @Test
    void ignoresOtherNodesAndOtherAckClasses() throws Exception {
        guard.received(Node.of("notification", Map.of("id", "1", "from", "x@s.whatsapp.net"), new Node[0]));
        guard.received(message("3A6E498C"));
        guard.sent(Node.of("ack", Map.of("id", "3A6E498C", "class", "notification"), new Node[0]));

        scheduler.awaitIdle();

        assertThat(sent).extracting(n -> n.attributes().toMap().get("id")).containsExactly("3A6E498C");
    }

    /** Executor de agendamento que deixa o teste esperar as tarefas pendentes, sem sleep fixo. */
    private static final class ScheduledProbe {
        final java.util.concurrent.ScheduledThreadPoolExecutor executor = new java.util.concurrent.ScheduledThreadPoolExecutor(1);

        void awaitIdle() throws InterruptedException {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((!executor.getQueue().isEmpty() || executor.getActiveCount() > 0) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
        }
    }
}
