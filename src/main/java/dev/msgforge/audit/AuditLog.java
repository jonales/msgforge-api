// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * A trilha de auditoria do Manager (ADR 0018), na tabela {@code audit_event} por SQL direto, sem
 * entidade JPA (como o histórico, ADR 0008) e sem chave estrangeira: o registro sobrevive a quem foi
 * apagado depois.
 *
 * <p>Gravar nunca derruba o que está sendo auditado: dentro de uma transação, o registro sai só
 * depois do commit (numa transação própria), e qualquer falha vai só para o log.
 */
@Repository
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    /** Quem agiu foi a chave global (AUTHENTICATION_API_KEY). Não é e-mail válido, então não colide. */
    public static final String GLOBAL_KEY = "@chave";
    /** Login com e-mail que não é de nenhuma conta: o texto digitado não é guardado (ADR 0018). */
    public static final String UNKNOWN_ACCOUNT = "@desconhecida";

    /** O que a tela pode filtrar. */
    public enum Filter { ALL, LOGINS, FAILURES, PEOPLE }

    /**
     * Uma página da consulta.
     *
     * @param organizations as organizações que a pessoa pode auditar; null = a plataforma inteira
     * @param before        só registros com id menor (a página seguinte); null = do mais novo
     */
    public record Query(Set<UUID> organizations, Filter filter, Long before, int limit) {}

    /** @param more há registros mais antigos que estes */
    public record Page(List<AuditEntry> entries, boolean more) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate ownTransaction;

    public AuditLog(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Grava (depois do commit, se houver transação). Sem IP no evento, usa o da requisição atual. */
    public void record(AuditEvent event) {
        var withIp = event.ip() != null ? event : new AuditEvent(event.action(), event.outcome(),
                event.organizationId(), event.actorUserId(), event.actorLabel(), event.target(), event.detail(),
                currentIp());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write(withIp, true);
                }
            });
        } else {
            write(withIp, false);
        }
    }

    private void write(AuditEvent event, boolean afterCommit) {
        try {
            if (afterCommit) {
                ownTransaction.executeWithoutResult(status -> insert(event));
            } else {
                insert(event);
            }
        } catch (RuntimeException e) {
            log.warn("Auditoria: não gravou {} {} ({})", event.action(), event.outcome(), e.getClass().getSimpleName());
        }
    }

    private void insert(AuditEvent event) {
        jdbc.update("""
                INSERT INTO audit_event (action, outcome, organization_id, actor_user_id, actor_label, target, detail, ip)
                VALUES (:action, :outcome, :org, :actor, :label, :target, :detail, :ip)
                """, new MapSqlParameterSource()
                .addValue("action", event.action().name())
                .addValue("outcome", event.outcome().name())
                .addValue("org", event.organizationId())
                .addValue("actor", event.actorUserId())
                .addValue("label", cut(event.actorLabel() == null ? UNKNOWN_ACCOUNT : event.actorLabel(), 254))
                .addValue("target", cut(event.target(), 254))
                .addValue("detail", cut(event.detail(), 200))
                .addValue("ip", cut(event.ip(), 45)));
    }

    /**
     * Do mais novo para o mais antigo. Numa organização entram as ações feitas nela e os logins e
     * logouts de quem é membro dela; as tentativas com conta inexistente e as da chave global (sem
     * organização nem pessoa) só aparecem para a plataforma inteira.
     */
    public Page find(Query query) {
        var params = new MapSqlParameterSource().addValue("limit", query.limit() + 1);
        var where = new ArrayList<String>();
        if (query.organizations() != null) {
            params.addValue("orgs", query.organizations());
            where.add("""
                    (organization_id IN (:orgs) OR (organization_id IS NULL AND actor_user_id IN
                        (SELECT user_id FROM membership WHERE organization_id IN (:orgs))))""");
        }
        switch (query.filter()) {
            case LOGINS -> where.add("action IN ('LOGIN', 'LOGOUT')");
            case FAILURES -> where.add("outcome <> 'OK'");
            case PEOPLE -> where.add("action NOT IN ('LOGIN', 'LOGOUT')");
            case ALL -> { }
        }
        if (query.before() != null) {
            params.addValue("before", query.before());
            where.add("id < :before");
        }
        var sql = "SELECT id, at, action, outcome, organization_id, actor_user_id, actor_label, target, detail, ip "
                + "FROM audit_event" + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                + " ORDER BY id DESC LIMIT :limit";
        var rows = jdbc.query(sql, params, AuditLog::entry);
        var more = rows.size() > query.limit();
        return new Page(more ? rows.subList(0, query.limit()) : rows, more);
    }

    /** Apaga até {@code batch} registros mais velhos que {@code cutoff}; devolve quantos saíram. */
    public int deleteOlderThan(Instant cutoff, int batch) {
        return jdbc.update("""
                DELETE FROM audit_event WHERE id IN
                    (SELECT id FROM audit_event WHERE at < :cutoff ORDER BY id LIMIT :batch)
                """, new MapSqlParameterSource()
                .addValue("cutoff", Timestamp.from(cutoff))
                .addValue("batch", batch));
    }

    private static AuditEntry entry(ResultSet rs, int row) throws SQLException {
        return new AuditEntry(rs.getLong("id"), rs.getTimestamp("at").toInstant(),
                AuditAction.valueOf(rs.getString("action")), AuditOutcome.valueOf(rs.getString("outcome")),
                rs.getObject("organization_id", UUID.class), rs.getObject("actor_user_id", UUID.class),
                rs.getString("actor_label"), rs.getString("target"), rs.getString("detail"), rs.getString("ip"));
    }

    private static String currentIp() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request
                ? request.getRequest().getRemoteAddr() : null;
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
