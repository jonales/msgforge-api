// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
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
        return save(instanceId, key, pushName, messageType, message, status, timestamp, null);
    }

    /**
     * Como o outro {@code save}, com quem enviou. Se a mensagem já está gravada sem autor (o eco do
     * próprio envio pode chegar antes da resposta do envio), só o autor é preenchido.
     */
    public boolean save(UUID instanceId, Key key, String pushName, String messageType, Map<String, Object> message,
                        String status, long timestamp, SentBy sentBy) {
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
                .addValue("status", status != null && STATUS_ORDER.contains(status) ? status : "PENDING")
                .addValue("ts", timestamp)
                .addValue("sentBy", sentBy == null ? null : sentBy.kind().name())
                .addValue("sentByUser", sentBy == null ? null : sentBy.userId())
                .addValue("sentByName", sentBy == null ? null : sentBy.name());
        return jdbc.update("""
                INSERT INTO message (id, instance_id, key_id, remote_jid, from_me, participant, push_name,
                                     message_type, message, status, message_timestamp,
                                     sent_by, sent_by_user_id, sent_by_name)
                VALUES (:id, :instance, :keyId, :remoteJid, :fromMe, :participant, :pushName,
                        :type, CAST(:message AS jsonb), :status, :ts, :sentBy, :sentByUser, :sentByName)
                ON CONFLICT ON CONSTRAINT uk_message_key DO UPDATE
                    SET sent_by = EXCLUDED.sent_by, sent_by_user_id = EXCLUDED.sent_by_user_id,
                        sent_by_name = EXCLUDED.sent_by_name
                    WHERE message.sent_by IS NULL AND EXCLUDED.sent_by IS NOT NULL
                """, params) == 1;
    }

    /** Só avança o status (ver {@link #STATUS_ORDER}); devolve false se nada mudou. */
    public boolean updateStatus(UUID instanceId, String remoteJid, String keyId, boolean fromMe, String status) {
        var rank = status == null ? -1 : STATUS_ORDER.indexOf(status);   // List.of não aceita null
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

    /**
     * Mensagem apagada para todos: o conteúdo some e fica o aviso, como no WhatsApp (um
     * {@code protocolMessage} REVOKE, no formato do Baileys). Devolve false se ela não está gravada.
     */
    public boolean markRevoked(UUID instanceId, String remoteJid, String keyId) {
        var params = new MapSqlParameterSource()
                .addValue("instance", instanceId)
                .addValue("remoteJid", remoteJid)
                .addValue("keyId", keyId)
                .addValue("message", json.writeValueAsString(Map.of(REVOKED_TYPE, Map.of("type", "REVOKE"))))
                .addValue("type", REVOKED_TYPE);
        return jdbc.update("""
                UPDATE message SET message_type = :type, message = CAST(:message AS jsonb)
                WHERE instance_id = :instance AND remote_jid = :remoteJid AND key_id = :keyId
                """, params) > 0;
    }

    /** Tipo gravado no lugar da mensagem apagada. */
    public static final String REVOKED_TYPE = "protocolMessage";

    /**
     * Apaga até {@code batch} mensagens anteriores a {@code cutoff} (epoch em segundos); devolve
     * quantas apagou. Em lotes, para não segurar um lock grande numa tabela movimentada.
     */
    public int deleteOlderThan(long cutoff, int batch) {
        return jdbc.update("""
                DELETE FROM message WHERE id IN (
                    SELECT id FROM message WHERE message_timestamp < :cutoff LIMIT :batch)
                """, new MapSqlParameterSource().addValue("cutoff", cutoff).addValue("batch", batch));
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

    /**
     * O último nome de exibição (pushName) que {@code jid} mandou, numa conversa com ele ou num grupo;
     * vazio se ele nunca escreveu. É o nome que o WhatsApp mostra para quem não está nos contatos.
     */
    public java.util.Optional<String> lastPushName(UUID instanceId, String jid) {
        return jdbc.query("""
                SELECT push_name FROM message
                WHERE instance_id = :instance AND from_me = FALSE AND push_name IS NOT NULL AND push_name <> ''
                  AND (remote_jid = :jid OR participant = :jid)
                ORDER BY message_timestamp DESC LIMIT 1
                """, new MapSqlParameterSource().addValue("instance", instanceId).addValue("jid", jid),
                (rs, n) -> rs.getString(1)).stream().findFirst();
    }

    /** Quem enviou cada mensagem (pelo id), entre as {@code keyIds} da conversa; só as que têm autor. */
    public Map<String, SentBy> sentBy(UUID instanceId, String remoteJid, java.util.Collection<String> keyIds) {
        if (keyIds.isEmpty()) {
            return Map.of();
        }
        var params = new MapSqlParameterSource()
                .addValue("instance", instanceId)
                .addValue("remoteJid", remoteJid)
                .addValue("ids", keyIds);
        var out = new java.util.HashMap<String, SentBy>();
        jdbc.query("""
                SELECT key_id, sent_by, sent_by_user_id, sent_by_name FROM message
                WHERE instance_id = :instance AND remote_jid = :remoteJid AND from_me = TRUE
                  AND key_id IN (:ids) AND sent_by IS NOT NULL
                """, params, rs -> {
            out.put(rs.getString("key_id"), new SentBy(SentBy.Kind.valueOf(rs.getString("sent_by")),
                    rs.getObject("sent_by_user_id", UUID.class), rs.getString("sent_by_name")));
        });
        return out;
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
