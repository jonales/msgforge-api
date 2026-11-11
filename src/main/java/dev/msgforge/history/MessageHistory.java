package dev.msgforge.history;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tabela {@code message} por SQL direto: o insert precisa de ON CONFLICT (o WhatsApp reentrega
 * mensagens) e as conversas saem de um DISTINCT ON. Sem entidade JPA (ADR 0008).
 */
@Repository
public class MessageHistory {

    /** Ordem dos status: um status atrasado nunca rebaixa um mais novo (READ não volta a DELIVERY_ACK). */
    static final List<String> STATUS_ORDER =
            List.of("ERROR", "PENDING", "SERVER_ACK", "DELIVERY_ACK", "READ", "PLAYED");

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    public MessageHistory(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Key(String remoteJid, boolean fromMe, String id, String participant) {}

    public record Stored(UUID id, Key key, String pushName, String messageType, Map<String, Object> message,
                         long messageTimestamp, UUID instanceId, String status) {}

    public record Chat(String remoteJid, String pushName, Stored lastMessage) {}

    /** Grava se ainda não existe; devolve false para mensagem repetida. */
    public boolean save(UUID instanceId, Key key, String pushName, String messageType, Map<String, Object> message,
                        String status, long timestamp) {
        var params = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("instance", instanceId)
                .addValue("keyId", key.id())
                .addValue("remoteJid", key.remoteJid())
                .addValue("fromMe", key.fromMe())
                .addValue("participant", key.participant())
                .addValue("pushName", pushName)
                .addValue("type", messageType)
                .addValue("message", json.writeValueAsString(message))
                .addValue("status", STATUS_ORDER.contains(status) ? status : "PENDING")
                .addValue("ts", timestamp);
        return jdbc.update("""
                INSERT INTO message (id, instance_id, key_id, remote_jid, from_me, participant, push_name,
                                     message_type, message, status, message_timestamp)
                VALUES (:id, :instance, :keyId, :remoteJid, :fromMe, :participant, :pushName,
                        :type, CAST(:message AS jsonb), :status, :ts)
                ON CONFLICT ON CONSTRAINT uk_message_key DO NOTHING
                """, params) == 1;
    }

    /** Só avança o status (ver {@link #STATUS_ORDER}); devolve false se nada mudou. */
    public boolean updateStatus(UUID instanceId, String remoteJid, String keyId, boolean fromMe, String status) {
        var rank = STATUS_ORDER.indexOf(status);
        if (rank < 0) {
            return false;
        }
        var params = new MapSqlParameterSource()
                .addValue("instance", instanceId)
                .addValue("remoteJid", remoteJid)
                .addValue("keyId", keyId)
                .addValue("fromMe", fromMe)
                .addValue("status", status)
                .addValue("order", STATUS_ORDER.toArray(String[]::new))
                .addValue("rank", rank + 1);   // array_position começa em 1
        return jdbc.update("""
                UPDATE message SET status = :status
                WHERE instance_id = :instance AND remote_jid = :remoteJid AND key_id = :keyId AND from_me = :fromMe
                  AND array_position(CAST(:order AS varchar[]), status) < :rank
                """, params) == 1;
    }

    /** Filtros opcionais (null = qualquer); mais novas primeiro. {@code page} começa em 1. */
    public List<Stored> find(UUID instanceId, String remoteJid, Boolean fromMe, String keyId, int page, int size) {
        var params = filters(instanceId, remoteJid, fromMe, keyId)
                .addValue("limit", size)
                .addValue("offset", (long) (page - 1) * size);
        return jdbc.query("SELECT * FROM message" + where(remoteJid, fromMe, keyId)
                        + " ORDER BY message_timestamp DESC, created_at DESC LIMIT :limit OFFSET :offset",
                params, (rs, n) -> row(rs));
    }

    public long count(UUID instanceId, String remoteJid, Boolean fromMe, String keyId) {
        var total = jdbc.queryForObject("SELECT count(*) FROM message" + where(remoteJid, fromMe, keyId),
                filters(instanceId, remoteJid, fromMe, keyId), Long.class);
        return total == null ? 0 : total;
    }

    /** Uma linha por conversa, com a última mensagem e o último nome visto; mais recentes primeiro. */
    public List<Chat> chats(UUID instanceId) {
        return jdbc.query("""
                SELECT last.*, (SELECT m.push_name FROM message m
                                WHERE m.instance_id = last.instance_id AND m.remote_jid = last.remote_jid
                                  AND m.from_me = FALSE AND m.push_name IS NOT NULL
                                ORDER BY m.message_timestamp DESC LIMIT 1) AS chat_name
                FROM (SELECT DISTINCT ON (remote_jid) * FROM message
                      WHERE instance_id = :instance
                      ORDER BY remote_jid, message_timestamp DESC, created_at DESC) AS last
                ORDER BY last.message_timestamp DESC
                """, new MapSqlParameterSource("instance", instanceId),
                (rs, n) -> new Chat(rs.getString("remote_jid"), rs.getString("chat_name"), row(rs)));
    }

    private static MapSqlParameterSource filters(UUID instanceId, String remoteJid, Boolean fromMe, String keyId) {
        return new MapSqlParameterSource()
                .addValue("instance", instanceId)
                .addValue("remoteJid", remoteJid)
                .addValue("fromMe", fromMe)
                .addValue("keyId", keyId);
    }

    private static String where(String remoteJid, Boolean fromMe, String keyId) {
        var sql = new StringBuilder(" WHERE instance_id = :instance");
        if (remoteJid != null) sql.append(" AND remote_jid = :remoteJid");
        if (fromMe != null) sql.append(" AND from_me = :fromMe");
        if (keyId != null) sql.append(" AND key_id = :keyId");
        return sql.toString();
    }

    private Stored row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Stored(rs.getObject("id", UUID.class),
                new Key(rs.getString("remote_jid"), rs.getBoolean("from_me"), rs.getString("key_id"),
                        rs.getString("participant")),
                rs.getString("push_name"), rs.getString("message_type"),
                json.readValue(rs.getString("message"), MAP),
                rs.getLong("message_timestamp"), rs.getObject("instance_id", UUID.class), rs.getString("status"));
    }
}
