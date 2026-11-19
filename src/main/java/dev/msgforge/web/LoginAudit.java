// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.web;

import dev.msgforge.access.AccessService;
import dev.msgforge.access.ManagerPrincipal;
import dev.msgforge.audit.AuditAction;
import dev.msgforge.audit.AuditEvent;
import dev.msgforge.audit.AuditLog;
import dev.msgforge.audit.AuditOutcome;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

/**
 * Auditoria do login do Manager (ADR 0018): entrada, falha, bloqueio e saída, com o IP. Na falha,
 * guarda a conta só se ela existir; senão, "conta inexistente", sem o texto digitado (que pode ser
 * uma senha no campo errado). Nunca guarda senha nem chave.
 *
 * <p>Um IP bloqueado pelo {@link LoginAttemptLimiter} gera um registro por janela, e não um por
 * tentativa: sem isso, quem insiste enche a tabela.
 */
@Component
public class LoginAudit {

    private static final int MAX_TRACKED = 10_000;

    private final AuditLog audit;
    private final AccessService access;
    private final Clock clock;
    private final Map<String, Instant> blockedLogged = new ConcurrentHashMap<>();

    @Autowired
    public LoginAudit(AuditLog audit, AccessService access) {
        this(audit, access, Clock.systemUTC());
    }

    LoginAudit(AuditLog audit, AccessService access, Clock clock) {
        this.audit = audit;
        this.access = access;
        this.clock = clock;
    }

    void succeeded(HttpServletRequest request, ManagerPrincipal who) {
        audit.record(event(AuditAction.LOGIN, AuditOutcome.OK, who, request));
    }

    void failed(HttpServletRequest request, AuthenticationException exception) {
        var outcome = switch (exception) {
            case LockedException _ -> AuditOutcome.BLOCKED;
            case DisabledException _ -> AuditOutcome.DISABLED;
            default -> AuditOutcome.FAILED;
        };
        if (outcome == AuditOutcome.BLOCKED && !firstBlockInWindow(request.getRemoteAddr())) {
            return;
        }
        ManagerPrincipal who = request.getParameter("apikey") != null ? ManagerPrincipal.GLOBAL_KEY
                : access.account(request.getParameter("username")).orElse(null);
        audit.record(event(AuditAction.LOGIN, outcome, who, request));
    }

    void loggedOut(HttpServletRequest request, Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof ManagerPrincipal who) {
            audit.record(event(AuditAction.LOGOUT, AuditOutcome.OK, who, request));
        }
    }

    /** @param who null = conta inexistente */
    private static AuditEvent event(AuditAction action, AuditOutcome outcome, ManagerPrincipal who,
                                    HttpServletRequest request) {
        var label = who == null ? AuditLog.UNKNOWN_ACCOUNT : who.globalKey() ? AuditLog.GLOBAL_KEY : who.email();
        return new AuditEvent(action, outcome, null, who == null ? null : who.userId(), label, null, null,
                request.getRemoteAddr());
    }

    private boolean firstBlockInWindow(String ip) {
        var now = clock.instant();
        var window = LoginAttemptLimiter.WINDOW;
        if (blockedLogged.size() > MAX_TRACKED) {
            blockedLogged.values().removeIf(at -> Duration.between(at, now).compareTo(window) >= 0);
        }
        var key = ip == null ? "" : ip;
        var first = new boolean[1];
        blockedLogged.compute(key, (k, last) -> {
            if (last == null || Duration.between(last, now).compareTo(window) >= 0) {
                first[0] = true;
                return now;
            }
            return last;
        });
        return first[0];
    }
}
