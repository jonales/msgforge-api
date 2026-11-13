package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.event.outbox.Outbox;
import dev.msgforge.event.outbox.Outbox.Entry;
import dev.msgforge.event.outbox.OutboxListener;
import dev.msgforge.event.outbox.OutboxProperties;
import dev.msgforge.event.outbox.OutboxRetention;
import dev.msgforge.event.outbox.OutboxWorker;
import dev.msgforge.support.IntegrationTest;
import dev.msgforge.support.WebhookReceiver.Received;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Outbox das entregas (ADR 0012) contra o PostgreSQL de verdade.
 *
 * <p>Os contextos de teste em cache (um por {@code @TestPropertySource}) compartilham o banco, e
 * cada um tem o seu trabalhador: na prática, vários nós. Por isso os testes que chamam
 * {@link Outbox#claim} direto usam linhas com a próxima tentativa daqui a um dia, que nenhum
 * trabalhador pega, e um "agora" ({@code asOf}) adiantado.
 */
class OutboxIT extends IntegrationTest {

    @Autowired
    private Outbox outbox;

    @Autowired
    private OutboxWorker worker;

    @Autowired
    private OutboxRetention retention;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSourceProperties dataSource;

    private static List<Received> receivedFor(String name) {
        return webhooks.received().stream().filter(r -> r.path().startsWith("/" + name + "/")).toList();
    }

    private Map<String, Object> rowOf(String orderKey, String event) {
        return jdbc.queryForMap("SELECT state, attempts, last_error, payload::text AS payload FROM outbox "
                + "WHERE order_key = ? AND event = ? ORDER BY id LIMIT 1", orderKey, event);
    }

    @Test
    void lastFailureMarksTheEntryDeadAndTheInstanceMovesOn() {
        var name = uniqueName("morto");
        webhooks.respondWith(path -> path.equals("/" + name + "/qrcode-updated") ? 503 : 200);

        post("/instance/create", """
                {"instanceName":"%s","qrcode":true,"webhook":{"url":"%s","byEvents":true}}
                """.formatted(name, webhooks.url("/" + name)));

        // 3 tentativas (WEBHOOK_RETRY_MAX_ATTEMPTS dos testes) e a fila da instância segue
        webhooks.await(r -> r.path().equals("/" + name + "/connection-update"), Duration.ofSeconds(5));
        assertThat(receivedFor(name)).extracting(Received::path).containsExactly(
                "/" + name + "/qrcode-updated", "/" + name + "/qrcode-updated", "/" + name + "/qrcode-updated",
                "/" + name + "/connection-update");

        var dead = rowOf(name, "qrcode.updated");
        assertThat(dead.get("state")).isEqualTo("MORTO");
        assertThat(dead.get("attempts")).isEqualTo(3);
        assertThat(dead.get("last_error")).isEqualTo("HTTP 503");

        var done = waitForState(name, "connection.update", "FEITO");
        // O corpo entregue é o texto gravado, byte a byte
        assertThat(receivedFor(name).getLast().body()).isEqualTo(done.get("payload"));
    }

    @Test
    void entriesLeftBehindByACrashAreDeliveredAfterRestartInOrder() {
        var name = uniqueName("queda");
        worker.stop();   // "processo caído": este nó não entrega
        try {
            var url = webhooks.url("/" + name + "/hook");
            outbox.insert(List.of(
                    entry(name, "connection.update", url, "{\"n\":1}"),
                    entry(name, "messages.upsert", url, "{\"n\":2}"),
                    entry(name, "messages.update", url, "{\"n\":3}")));
            // A primeira estava no meio da entrega quando o nó caiu, com o prazo já vencido
            jdbc.update("UPDATE outbox SET state = 'EM_ENVIO', attempts = 1, "
                    + "next_attempt_at = now() - interval '1 second' WHERE order_key = ? AND event = ?",
                    name, "connection.update");
        } finally {
            worker.start();   // "reinício"
        }

        webhooks.await(r -> receivedFor(name).size() >= 3, Duration.ofSeconds(10));
        assertThat(receivedFor(name)).extracting(Received::body)
                .containsExactly("{\"n\":1}", "{\"n\":2}", "{\"n\":3}");
        waitForState(name, "messages.update", "FEITO");
        assertThat(rowOf(name, "connection.update").get("attempts")).isEqualTo(2);
    }

    @Test
    void retentionDeletesOnlyOldDoneEntries() {
        var key = uniqueName("retencao");
        insertRaw(key, "FEITO", "now() - interval '48 hours'");
        insertRaw(key, "FEITO", "now() - interval '1 hour'");
        insertRaw(key, "MORTO", "now() - interval '48 hours'");

        retention.purge();

        assertThat(jdbc.queryForList("SELECT state FROM outbox WHERE order_key = ? ORDER BY id", String.class, key))
                .containsExactly("FEITO", "MORTO");
    }

    /**
     * Vários "nós" pegando ao mesmo tempo: nenhuma linha sai duas vezes, cada chave tem no máximo
     * uma linha EM_ENVIO, e as linhas de cada chave saem na ordem do id.
     */
    @Test
    void concurrentClaimsTakeOnlyTheHeadOfEachKey() throws Exception {
        var prefix = uniqueName("disputa");
        int keys = 8;
        int perKey = 5;
        for (int i = 0; i < perKey; i++) {
            for (int k = 0; k < keys; k++) {
                insertRaw(prefix + "-" + k, "PENDENTE", "now() + interval '1 day'");
            }
        }
        var asOf = Instant.now().plus(Duration.ofDays(2));
        var delivered = new HashMap<String, List<Long>>();
        var total = 0;
        try {
            while (total < keys * perKey) {
                var round = new ConcurrentLinkedQueue<Entry>();
                var start = new CountDownLatch(1);
                var failures = new AtomicInteger();
                try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (int t = 0; t < 4; t++) {
                        pool.submit(() -> {
                            try {
                                start.await();
                                round.addAll(outbox.claim(100, Duration.ofMinutes(5), asOf).stream()
                                        .filter(e -> e.orderKey().startsWith(prefix)).toList());
                            } catch (Exception e) {
                                failures.incrementAndGet();
                            }
                            return null;
                        });
                    }
                    start.countDown();
                }
                assertThat(failures).hasValue(0);
                assertThat(round).as("uma linha por chave por rodada")
                        .extracting(Entry::orderKey).doesNotHaveDuplicates().hasSize(keys);
                for (var e : round) {
                    delivered.computeIfAbsent(e.orderKey(), k -> new ArrayList<>()).add(e.id());
                    outbox.markDone(e.id());
                }
                total += round.size();
            }
            assertThat(delivered).hasSize(keys);
            delivered.values().forEach(ids -> assertThat(ids).hasSize(perKey).isSorted());
        } finally {
            jdbc.update("DELETE FROM outbox WHERE order_key LIKE ?", prefix + "-%");
        }
    }

    /** Multi-nó: o NOTIFY que outro nó faz ao gravar acorda o trabalhador deste. */
    @Test
    void notifyFromAnotherNodeWakesTheWorker() throws Exception {
        var otherWorker = Mockito.mock(OutboxWorker.class);
        var listener = new OutboxListener(dataSource, otherWorker, new OutboxProperties(true, 24, 1000, 4, 60));
        listener.start();
        try {
            // O LISTEN começa numa thread própria: repete o NOTIFY até ele chegar
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            boolean woke = false;
            while (!woke && System.nanoTime() < deadline) {
                jdbc.execute("NOTIFY msgforge_outbox");
                Thread.sleep(50);
                woke = !Mockito.mockingDetails(otherWorker).getInvocations().isEmpty();
            }
            assertThat(woke).as("wake() depois do NOTIFY").isTrue();
        } finally {
            listener.stop();
        }
    }

    private static Outbox.NewEntry entry(String key, String event, String url, String payload) {
        return new Outbox.NewEntry(Outbox.Type.WEBHOOK, key, event, url, Map.of(), false, payload);
    }

    private void insertRaw(String key, String state, String timeSql) {
        jdbc.update("INSERT INTO outbox (type, order_key, event, destination, payload, state, next_attempt_at, "
                + "updated_at) VALUES ('WEBHOOK', ?, 'send.message', 'http://127.0.0.1:9/x', '{}', ?, "
                + timeSql + ", " + timeSql + ")", key, state);
    }

    private Map<String, Object> waitForState(String key, String event, String state) {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        Map<String, Object> row;
        do {
            row = rowOf(key, event);
            if (state.equals(row.get("state"))) {
                return row;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Linha " + key + "/" + event + " não chegou a " + state + ": " + row);
    }
}
