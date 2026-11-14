package dev.msgforge.event.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tabela {@code outbox} por SQL direto (ADR 0012): uma linha por evento e destino. A ordem de
 * entrega é a do {@code id} dentro de cada chave ({@code type} + {@code order_key}, a instância).
 */
@Repository
public class Outbox {

    /**
     * Destino da entrega. Um tipo novo exige trocar o CHECK {@code ck_outbox_type} numa migration.
     * {@code EMAIL} (ADR 0011): a chave é o destinatário, o destino é o endereço e o payload é o
     * e-mail montado ({@code EmailMessage}).
     */
    public enum Type { WEBHOOK, WEBHOOK_GLOBAL, RABBITMQ, EMAIL }

    public enum State { PENDENTE, EM_ENVIO, FEITO, MORTO }

    /**
     * @param destination         URL do webhook ou exchange do RabbitMQ
     * @param checkPrivateNetwork aplica o {@code PrivateNetworkGuard} na entrega
     * @param payload             o envelope já serializado, exatamente como sai para o destino
     */
    public record NewEntry(Type type, String orderKey, String event, String destination,
                           Map<String, String> headers, boolean checkPrivateNetwork, String payload) {}

    /** Linha pega pelo trabalhador; {@code attempts} já conta a tentativa em curso. */
    public record Entry(long id, Type type, String orderKey, String event, String destination,
                        Map<String, String> headers, boolean checkPrivateNetwork, String payload, int attempts) {}

    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;
    private final TransactionTemplate ownTransaction;

    public Outbox(NamedParameterJdbcTemplate jdbc, JsonMapper json, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Grava as entregas de um evento numa transação própria. Roda no afterCommit de quem
     * publicou, quando a transação original já terminou mas ainda está presa à thread.
     */
    public void insert(List<NewEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        var batch = entries.stream().map(e -> new MapSqlParameterSource()
                        .addValue("type", e.type().name())
                        .addValue("orderKey", e.orderKey())
                        .addValue("event", e.event())
                        .addValue("destination", e.destination())
                        .addValue("headers", json.writeValueAsString(e.headers() == null ? Map.of() : e.headers()))
                        .addValue("check", e.checkPrivateNetwork())
                        .addValue("payload", e.payload()))
                .toArray(MapSqlParameterSource[]::new);
        ownTransaction.executeWithoutResult(status -> {
            jdbc.batchUpdate("""
                    INSERT INTO outbox (type, order_key, event, destination, headers, check_private_network, payload)
                    VALUES (:type, :orderKey, :event, :destination, CAST(:headers AS jsonb), :check,
                            CAST(:payload AS json))
                    """, batch);
            // Acorda os trabalhadores dos outros nós (OutboxListener); sai só no commit
            jdbc.getJdbcTemplate().execute("NOTIFY " + OutboxListener.CHANNEL);
        });
    }

    /** Pega até {@code limit} entregas prontas, marcando-as EM_ENVIO por {@code lease}. */
    public List<Entry> claim(int limit, Duration lease) {
        return claim(limit, lease, null);
    }

    public List<Entry> claim(int limit, Duration lease, Instant asOf) {
        return claim(limit, lease, asOf, EnumSet.allOf(Type.class));
    }

    /**
     * Pega a cabeça de cada chave: a linha PENDENTE mais antiga, já no horário, sem outra linha
     * PENDENTE ou EM_ENVIO mais antiga na mesma chave. Assim a ordem por instância (ADR 0006)
     * vale também com retry: enquanto a cabeça espera a próxima tentativa, a chave inteira espera.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED}: dois nós nunca pegam a mesma linha. O
     * {@code c.state = 'PENDENTE'} é conferido de novo na versão mais nova da linha travada, então
     * uma linha que outro nó acabou de pegar fica de fora.
     *
     * @param asOf  "agora" para comparar com a próxima tentativa (testes); null usa o relógio do banco
     * @param types só os tipos que este nó sabe entregar (um nó sem SMTP deixa os e-mails para outro)
     */
    public List<Entry> claim(int limit, Duration lease, Instant asOf, Set<Type> types) {
        if (limit <= 0 || types.isEmpty()) {
            return List.of();
        }
        var params = new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("asOf", asOf == null ? null : asOf.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("leaseMs", lease.toMillis())
                .addValue("types", types.stream().map(Type::name).toList());
        return jdbc.query("""
                WITH now_ AS (SELECT COALESCE(CAST(:asOf AS timestamptz), now()) AS t)
                UPDATE outbox o
                   SET state = 'EM_ENVIO', attempts = o.attempts + 1,
                       next_attempt_at = (SELECT t FROM now_) + :leaseMs * interval '1 millisecond',
                       updated_at = now()
                 WHERE o.id IN (
                       SELECT c.id FROM outbox c
                        WHERE c.state = 'PENDENTE' AND c.next_attempt_at <= (SELECT t FROM now_)
                          AND c.type IN (:types)
                          AND NOT EXISTS (
                              SELECT 1 FROM outbox p
                               WHERE p.type = c.type AND p.order_key = c.order_key AND p.id < c.id
                                 AND p.state IN ('PENDENTE', 'EM_ENVIO'))
                        ORDER BY c.id
                        LIMIT :limit
                          FOR UPDATE SKIP LOCKED)
                RETURNING o.id, o.type, o.order_key, o.event, o.destination, o.headers,
                          o.check_private_network, o.payload, o.attempts
                """, params, this::entry).stream()
                .sorted(Comparator.comparingLong(Entry::id))
                .toList();
    }

    public void markDone(long id) {
        jdbc.update("""
                UPDATE outbox SET state = 'FEITO', last_error = NULL, updated_at = now()
                WHERE id = :id AND state = 'EM_ENVIO'
                """, new MapSqlParameterSource("id", id));
    }

    /** Volta a PENDENTE para tentar de novo daqui a {@code delay}; a chave continua esperando por ela. */
    public void markRetry(long id, String error, Duration delay) {
        jdbc.update("""
                UPDATE outbox SET state = 'PENDENTE', last_error = :error, updated_at = now(),
                                  next_attempt_at = now() + :delayMs * interval '1 millisecond'
                WHERE id = :id AND state = 'EM_ENVIO'
                """, new MapSqlParameterSource("id", id).addValue("error", truncate(error))
                .addValue("delayMs", delay.toMillis()));
    }

    /** Desiste da entrega: a chave segue para a próxima linha. */
    public void markDead(long id, String error) {
        jdbc.update("""
                UPDATE outbox SET state = 'MORTO', last_error = :error, updated_at = now()
                WHERE id = :id AND state = 'EM_ENVIO'
                """, new MapSqlParameterSource("id", id).addValue("error", truncate(error)));
    }

    /**
     * Retoma as entregas EM_ENVIO cujo prazo venceu (o nó caiu no meio da entrega): voltam a
     * PENDENTE, ou viram MORTO se já gastaram as tentativas. A entrega é "pelo menos uma vez": se
     * o nó só estava lento, o destino pode receber o evento duas vezes.
     */
    public int releaseExpired(int maxAttempts) {
        return jdbc.update("""
                UPDATE outbox
                   SET state = CASE WHEN attempts >= :max THEN 'MORTO' ELSE 'PENDENTE' END,
                       last_error = 'lease expired', next_attempt_at = now(), updated_at = now()
                 WHERE state = 'EM_ENVIO' AND next_attempt_at < now()
                """, new MapSqlParameterSource("max", maxAttempts));
    }

    /** Apaga até {@code batch} entregas FEITO mais velhas que {@code age}; devolve quantas saíram. */
    public int deleteDoneOlderThan(Duration age, int batch) {
        return jdbc.update("""
                DELETE FROM outbox WHERE id IN (
                    SELECT id FROM outbox
                     WHERE state = 'FEITO' AND updated_at < now() - :ageMs * interval '1 millisecond'
                     LIMIT :batch)
                """, new MapSqlParameterSource("ageMs", age.toMillis()).addValue("batch", batch));
    }

    private Entry entry(ResultSet rs, int row) throws SQLException {
        return new Entry(rs.getLong("id"), Type.valueOf(rs.getString("type")), rs.getString("order_key"),
                rs.getString("event"), rs.getString("destination"), json.readValue(rs.getString("headers"), HEADERS),
                rs.getBoolean("check_private_network"), rs.getString("payload"), rs.getInt("attempts"));
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }
}
