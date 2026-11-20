// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * A configuração do SMTP feita pelo Manager (ADR 0019): uma linha só ({@code smtp_settings}, id 1), por SQL
 * direto. A senha chega e sai daqui já cifrada; quem decifra é o {@link EmailSender}.
 */
@Repository
public class SmtpSettingsStore {

    /**
     * Uma linha da tabela.
     *
     * @param passwordEncrypted a senha cifrada pelo {@code SecretCipher}; null sem login
     * @param updatedBy         quem salvou por último (e-mail ou {@code @chave})
     */
    public record Row(String host, int port, String username, String passwordEncrypted, String from,
                      boolean starttls, int timeoutMs, Instant updatedAt, String updatedBy) {

        /** Nunca a senha, nem cifrada. */
        @Override
        public String toString() {
            return "SmtpSettings[" + host + ":" + port + ", user=" + (username != null) + "]";
        }
    }

    private final NamedParameterJdbcTemplate jdbc;

    public SmtpSettingsStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> find() {
        return jdbc.query("SELECT * FROM smtp_settings WHERE id = 1", new MapSqlParameterSource(), SmtpSettingsStore::row)
                .stream().findFirst();
    }

    /** A data da última mudança, para um nó saber se outro nó mudou a configuração. */
    public Optional<Instant> lastChange() {
        return jdbc.query("SELECT updated_at FROM smtp_settings WHERE id = 1", new MapSqlParameterSource(),
                (rs, n) -> rs.getTimestamp("updated_at").toInstant()).stream().findFirst();
    }

    public void save(Row row) {
        jdbc.update("""
                INSERT INTO smtp_settings (id, host, port, username, password_encrypted, from_address, starttls,
                                           timeout_ms, updated_at, updated_by)
                VALUES (1, :host, :port, :username, :password, :from, :starttls, :timeout, now(), :by)
                ON CONFLICT (id) DO UPDATE SET host = EXCLUDED.host, port = EXCLUDED.port,
                    username = EXCLUDED.username, password_encrypted = EXCLUDED.password_encrypted,
                    from_address = EXCLUDED.from_address, starttls = EXCLUDED.starttls,
                    timeout_ms = EXCLUDED.timeout_ms, updated_at = now(), updated_by = EXCLUDED.updated_by
                """, new MapSqlParameterSource()
                .addValue("host", row.host()).addValue("port", row.port()).addValue("username", row.username())
                .addValue("password", row.passwordEncrypted()).addValue("from", row.from())
                .addValue("starttls", row.starttls()).addValue("timeout", row.timeoutMs())
                .addValue("by", row.updatedBy()));
    }

    /** Volta ao SMTP do ambiente. */
    public boolean delete() {
        return jdbc.update("DELETE FROM smtp_settings WHERE id = 1", new MapSqlParameterSource()) > 0;
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getString("host"), rs.getInt("port"), rs.getString("username"),
                rs.getString("password_encrypted"), rs.getString("from_address"), rs.getBoolean("starttls"),
                rs.getInt("timeout_ms"), rs.getTimestamp("updated_at").toInstant(), rs.getString("updated_by"));
    }
}
