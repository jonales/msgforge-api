package dev.msgforge.event.outbox;

import dev.msgforge.config.MsgforgeProperties;
import dev.msgforge.event.WebhookSender;
import dev.msgforge.event.WebhookSender.Attempt;
import dev.msgforge.event.outbox.Outbox.Entry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Entrega o que está na outbox (ADR 0012). Um laço pega as cabeças das chaves com
 * {@link Outbox#claim} e entrega cada uma numa virtual thread; cada linha é uma tentativa só, e as
 * novas tentativas são agendadas na própria linha (espera exponencial da política de retry do
 * webhook). Como só a cabeça de cada chave é pega, a ordem por instância (ADR 0006) continua valendo,
 * e vale entre nós: o {@code SKIP LOCKED} impede dois nós de pegarem a mesma linha.
 *
 * <p>O laço acorda com {@link #wake()} (linha nova, entrega terminada, hora de um retry) e, de
 * qualquer jeito, a cada {@code OUTBOX_POLL_INTERVAL_MS}: a varredura cobre as linhas gravadas por
 * outros nós e os sinais perdidos.
 */
@Component
@ConditionalOnProperty(prefix = "msgforge.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    private final Outbox outbox;
    private final WebhookSender sender;
    private final RabbitTemplate rabbit;
    private final OutboxProperties properties;
    private final Duration lease;
    private final Semaphore inFlight;

    private final Object signal = new Object();
    private boolean signalled;
    private volatile boolean running;
    private Thread loop;
    private ExecutorService deliveries;

    public OutboxWorker(Outbox outbox, WebhookSender sender, RabbitTemplate rabbit, OutboxProperties properties,
                        MsgforgeProperties msgforge) {
        this.outbox = outbox;
        this.sender = sender;
        this.rabbit = rabbit;
        this.properties = properties;
        // Uma tentativa de webhook leva no máximo ~2x o timeout (conexão + resposta); o prazo fica acima disso
        var minimum = Duration.ofMillis(3 * Math.max(1, msgforge.webhook().requestTimeoutMs()));
        var configured = Duration.ofSeconds(Math.max(1, properties.leaseSeconds()));
        this.lease = configured.compareTo(minimum) < 0 ? minimum : configured;
        this.inFlight = new Semaphore(Math.max(1, properties.maxInFlight()));
    }

    /** Acorda o laço agora (há linha nova ou uma entrega terminou). Barato; pode chamar à vontade. */
    public void wake() {
        synchronized (signal) {
            signalled = true;
            signal.notifyAll();
        }
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        deliveries = Executors.newVirtualThreadPerTaskExecutor();
        loop = Thread.ofVirtual().name("outbox-worker").start(this::run);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        wake();
        try {
            loop.join(Duration.ofSeconds(5));
            deliveries.shutdown();
            // O que não terminar a tempo fica EM_ENVIO e é retomado quando o prazo vencer
            if (!deliveries.awaitTermination(5, TimeUnit.SECONDS)) {
                log.info("Outbox: entregas em curso no desligamento serão retomadas depois de {} s",
                        lease.toSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        long lastRelease = 0;
        while (running) {
            boolean more = false;
            try {
                var now = System.nanoTime();
                if (now - lastRelease >= TimeUnit.MILLISECONDS.toNanos(properties.pollIntervalMs())) {
                    var released = outbox.releaseExpired(sender.maxAttempts());
                    if (released > 0) {
                        log.info("Outbox: {} entrega(s) abandonada(s) em EM_ENVIO retomada(s)", released);
                    }
                    lastRelease = now;
                }
                more = claimAndDeliver();
            } catch (RuntimeException e) {
                log.warn("Outbox: varredura falhou (tenta de novo em {} ms): {}", properties.pollIntervalMs(),
                        e.getMessage());
            }
            if (!more) {
                await(properties.pollIntervalMs());
            }
        }
    }

    /** @return true se pode haver mais linhas prontas agora (o lote veio cheio) */
    private boolean claimAndDeliver() {
        var free = inFlight.availablePermits();
        if (free == 0) {
            return false;   // wake() chega quando uma entrega terminar
        }
        var claimed = outbox.claim(free, lease);
        for (var entry : claimed) {
            inFlight.acquireUninterruptibly();
            deliveries.execute(() -> {
                try {
                    deliver(entry);
                } finally {
                    inFlight.release();
                    wake();   // a próxima linha da mesma chave já pode sair
                }
            });
        }
        return claimed.size() == free;
    }

    void deliver(Entry entry) {
        Attempt result;
        try {
            result = switch (entry.type()) {
                case WEBHOOK, WEBHOOK_GLOBAL -> sender.attempt(entry.destination(), entry.event(),
                        entry.payload().getBytes(StandardCharsets.UTF_8), entry.headers(),
                        entry.checkPrivateNetwork());
                case RABBITMQ -> publishRabbit(entry);
            };
        } catch (RuntimeException e) {
            result = new Attempt.Retry(String.valueOf(e.getMessage()));
        }

        try {
            switch (result) {
                case Attempt.Delivered _ -> outbox.markDone(entry.id());
                case Attempt.Failed f -> {
                    log.warn("Entrega {} {} para {} recusada: {} (sem retry)", entry.type(), entry.event(),
                            entry.destination(), f.reason());
                    outbox.markDead(entry.id(), f.reason());
                }
                case Attempt.Retry r when entry.attempts() >= sender.maxAttempts() -> {
                    log.warn("Entrega {} {} para {} falhou após {} tentativa(s): {}", entry.type(), entry.event(),
                            entry.destination(), entry.attempts(), r.reason());
                    outbox.markDead(entry.id(), r.reason());
                }
                case Attempt.Retry r -> {
                    var delay = Duration.ofMillis(sender.retryDelayMs(entry.attempts()));
                    log.debug("Entrega {} {} para {} falhou (tentativa {}/{}): {}", entry.type(), entry.event(),
                            entry.destination(), entry.attempts(), sender.maxAttempts(), r.reason());
                    outbox.markRetry(entry.id(), r.reason(), delay);
                    wakeAfter(delay);
                }
            }
        } catch (RuntimeException e) {
            // Sem conseguir gravar o resultado, a linha fica EM_ENVIO e volta quando o prazo vencer
            log.warn("Outbox: resultado da entrega {} não gravado: {}", entry.id(), e.getMessage());
        }
    }

    private Attempt publishRabbit(Entry entry) {
        var props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        var message = new Message(entry.payload().getBytes(StandardCharsets.UTF_8), props);
        rabbit.send(entry.destination(), entry.orderKey() + "." + entry.event(), message);
        return new Attempt.Delivered();
    }

    /** Acorda o laço quando a próxima tentativa vencer, sem esperar a varredura. */
    private void wakeAfter(Duration delay) {
        if (delay.toMillis() >= properties.pollIntervalMs()) {
            return;   // a varredura periódica chega antes ou junto
        }
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(delay);
                wake();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void await(long millis) {
        synchronized (signal) {
            if (!signalled && running) {
                try {
                    signal.wait(Math.max(1, millis));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
            signalled = false;
        }
    }
}
