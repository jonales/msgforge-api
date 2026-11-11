package dev.msgforge.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OrderedExecutorTest {

    private final OrderedExecutor ordered =
            new OrderedExecutor(Executors.newVirtualThreadPerTaskExecutor(), 10_000);

    @Test
    void keepsSubmissionOrderPerKeyUnderConcurrency() throws Exception {
        var keys = List.of("a", "b", "c", "d");
        var seen = new ConcurrentHashMap<String, List<Integer>>();
        keys.forEach(k -> seen.put(k, Collections.synchronizedList(new ArrayList<>())));
        int perKey = 2_000;
        var done = new CountDownLatch(keys.size() * perKey);

        // Um produtor por chave (ordem definida por produtor), todos ao mesmo tempo
        try (var producers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var key : keys) {
                producers.submit(() -> {
                    for (int i = 0; i < perKey; i++) {
                        int n = i;
                        ordered.submit(key, () -> {
                            seen.get(key).add(n);
                            done.countDown();
                        });
                        if (i % 100 == 0) {
                            Thread.yield();   // deixa filas esvaziarem e serem recriadas no meio
                        }
                    }
                });
            }
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        for (var key : keys) {
            assertThat(seen.get(key)).hasSize(perKey).isSorted();
        }
    }

    @Test
    void differentKeysRunInParallel() throws Exception {
        var blockA = new CountDownLatch(1);
        var ranB = new CountDownLatch(1);

        ordered.submit("a", () -> await(blockA));
        ordered.submit("b", ranB::countDown);

        assertThat(ranB.await(2, TimeUnit.SECONDS)).as("b não espera a fila de a").isTrue();
        blockA.countDown();
    }

    @Test
    void rejectsTasksAboveTheLimitAndAFailureDoesNotStopTheLane() throws Exception {
        var limited = new OrderedExecutor(Executors.newVirtualThreadPerTaskExecutor(), 2);
        var block = new CountDownLatch(1);
        var ran = new AtomicInteger();
        var finished = new CountDownLatch(1);

        var started = new CountDownLatch(1);
        assertThat(limited.submit("k", () -> { started.countDown(); await(block); })).isTrue();
        assertThat(started.await(2, TimeUnit.SECONDS)).as("1ª tarefa saiu da fila e está rodando").isTrue();
        assertThat(limited.submit("k", () -> { throw new IllegalStateException("x"); })).isTrue();
        assertThat(limited.submit("k", () -> { ran.incrementAndGet(); finished.countDown(); })).isTrue();
        waitUntil(() -> limited.pending("k") == 2);
        assertThat(limited.submit("k", ran::incrementAndGet)).as("fila cheia").isFalse();

        block.countDown();
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(ran.get()).isEqualTo(1);
        waitUntil(() -> limited.pending("k") == 0);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condição não satisfeita a tempo");
            }
            Thread.sleep(5);
        }
    }
}
