package dev.msgforge.event.outbox;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * {@code LISTEN} do PostgreSQL: acorda o {@link OutboxWorker} quando outro nó grava na outbox
 * (o {@link Outbox#insert} faz o {@code NOTIFY} na mesma transação). Usa uma conexão própria,
 * fora do pool, porque ela fica presa esperando notificações. Se a conexão cair, tenta de novo;
 * enquanto isso, a varredura periódica do trabalhador cobre as linhas novas.
 */
@Component
@ConditionalOnProperty(prefix = "msgforge.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxListener implements SmartLifecycle {

    static final String CHANNEL = "msgforge_outbox";
    private static final Logger log = LoggerFactory.getLogger(OutboxListener.class);
    private static final Duration RECONNECT = Duration.ofSeconds(5);

    private final DataSourceProperties dataSource;
    private final OutboxWorker worker;
    private final OutboxProperties properties;
    private volatile boolean running;
    private volatile Connection connection;
    private Thread loop;

    public OutboxListener(DataSourceProperties dataSource, OutboxWorker worker, OutboxProperties properties) {
        this.dataSource = dataSource;
        this.worker = worker;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        loop = Thread.ofVirtual().name("outbox-listener").start(this::run);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        loop.interrupt();
        closeQuietly(connection);
        try {
            loop.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        while (running) {
            try (var conn = DriverManager.getConnection(dataSource.determineUrl(), dataSource.determineUsername(),
                    dataSource.determinePassword())) {
                connection = conn;
                try (var statement = conn.createStatement()) {
                    statement.execute("LISTEN " + CHANNEL);
                }
                var pg = conn.unwrap(PGConnection.class);
                while (running) {
                    var notifications = pg.getNotifications((int) Math.max(1, properties.pollIntervalMs()));
                    if (notifications != null && notifications.length > 0) {
                        worker.wake();
                    }
                }
            } catch (SQLException e) {
                if (!running) {
                    return;
                }
                log.warn("Outbox: LISTEN interrompido ({}); reconecta em {} s", e.getMessage(), RECONNECT.toSeconds());
                try {
                    Thread.sleep(RECONNECT);
                } catch (InterruptedException ie) {
                    return;
                }
            } finally {
                connection = null;
            }
        }
    }

    private static void closeQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.close();
        } catch (SQLException ignored) {
            // desligando
        }
    }
}
