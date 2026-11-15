// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.bot;

import dev.msgforge.bot.BotRules.Session;
import dev.msgforge.bot.BotRules.Status;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** O estado do bot em cada conversa ({@code bot_session}), por SQL direto. */
@Repository
public class BotSessions {

    private final JdbcTemplate jdbc;

    public BotSessions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Session> find(UUID instanceId, String remoteJid) {
        return jdbc.query("""
                SELECT status, paused_until, last_activity_at FROM bot_session
                WHERE instance_id = ? AND remote_jid = ?""", (rs, n) -> {
            var until = rs.getTimestamp("paused_until");
            return new Session(Status.valueOf(rs.getString("status")), until == null ? null : until.toInstant(),
                    rs.getTimestamp("last_activity_at").toInstant());
        }, instanceId, remoteJid).stream().findFirst();
    }

    public void save(UUID instanceId, String remoteJid, Session session) {
        jdbc.update("""
                INSERT INTO bot_session (instance_id, remote_jid, status, paused_until, last_activity_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT pk_bot_session DO UPDATE
                    SET status = EXCLUDED.status, paused_until = EXCLUDED.paused_until,
                        last_activity_at = EXCLUDED.last_activity_at""",
                instanceId, remoteJid, session.status().name(),
                session.pausedUntil() == null ? null : Timestamp.from(session.pausedUntil()),
                Timestamp.from(session.lastActivity()));
    }
}
