package dev.msgforge.event;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executa tarefas em ordem de chegada <b>por chave</b> (aqui: por instância) e em paralelo entre
 * chaves diferentes. Cada chave tem uma fila; uma única thread drena a fila de cada vez.
 *
 * <p>Uma fila que esvazia é removida do mapa, então a memória acompanha só as instâncias com
 * entregas pendentes. Cada fila tem um teto ({@code maxPending}); acima dele a tarefa nova é
 * recusada, para que um destino fora do ar não faça a memória crescer sem limite.
 */
public final class OrderedExecutor {

    private static final Logger log = LoggerFactory.getLogger(OrderedExecutor.class);

    private static final class Lane {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        boolean running;
        boolean retired;
    }

    private final ConcurrentHashMap<String, Lane> lanes = new ConcurrentHashMap<>();
    private final Executor executor;
    private final int maxPending;

    public OrderedExecutor(Executor executor, int maxPending) {
        this.executor = executor;
        this.maxPending = Math.max(1, maxPending);
    }

    /** @return false se a fila da chave já estava cheia (a tarefa foi descartada) */
    public boolean submit(String key, Runnable task) {
        while (true) {
            var lane = lanes.computeIfAbsent(key, k -> new Lane());
            synchronized (lane) {
                if (lane.retired) {
                    continue;   // a fila foi removida entre o computeIfAbsent e o lock: pega a nova
                }
                if (lane.queue.size() >= maxPending) {
                    return false;
                }
                lane.queue.add(task);
                if (lane.running) {
                    return true;
                }
                lane.running = true;
            }
            executor.execute(() -> drain(key, lane));
            return true;
        }
    }

    /** Tarefas ainda não iniciadas para a chave (para testes e métricas). */
    int pending(String key) {
        var lane = lanes.get(key);
        if (lane == null) {
            return 0;
        }
        synchronized (lane) {
            return lane.queue.size();
        }
    }

    private void drain(String key, Lane lane) {
        while (true) {
            Runnable next;
            synchronized (lane) {
                next = lane.queue.poll();
                if (next == null) {
                    lane.running = false;
                    lane.retired = true;
                    lanes.remove(key, lane);
                    return;
                }
            }
            try {
                next.run();
            } catch (RuntimeException e) {
                log.warn("Tarefa da fila {} falhou: {}", key, e.getMessage(), e);
            }
        }
    }
}
