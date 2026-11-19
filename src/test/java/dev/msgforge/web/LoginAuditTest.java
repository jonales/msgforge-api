// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.msgforge.access.AccessService;
import dev.msgforge.access.ManagerPrincipal;
import dev.msgforge.audit.AuditEvent;
import dev.msgforge.audit.AuditLog;
import dev.msgforge.audit.AuditOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;

class LoginAuditTest {

    private final AuditLog audit = mock(AuditLog.class);
    private final AccessService access = mock(AccessService.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-19T22:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final LoginAudit login = new LoginAudit(audit, access, clock);

    private static MockHttpServletRequest request(String ip, String username) {
        var request = new MockHttpServletRequest("POST", "/manager/login");
        request.setRemoteAddr(ip);
        request.setParameter("username", username);
        return request;
    }

    private AuditEvent recorded() {
        var captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(captor.capture());
        return captor.getValue();
    }

    @Test
    void anUnknownAccountKeepsNoTypedText() {
        when(access.account("algo-digitado")).thenReturn(Optional.empty());
        login.failed(request("203.0.113.1", "algo-digitado"), new BadCredentialsException("x"));

        var event = recorded();
        assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(event.actorLabel()).isEqualTo(AuditLog.UNKNOWN_ACCOUNT);
        assertThat(event.actorUserId()).isNull();
        assertThat(event.toString()).doesNotContain("algo-digitado");
    }

    @Test
    void aKnownAccountIsKeptAndADisabledUserToo() {
        var id = UUID.randomUUID();
        when(access.account("ana@exemplo.com")).thenReturn(Optional.of(new ManagerPrincipal(id, "ana@exemplo.com")));
        login.failed(request("203.0.113.2", "ana@exemplo.com"), new DisabledException("x"));

        var event = recorded();
        assertThat(event.outcome()).isEqualTo(AuditOutcome.DISABLED);
        assertThat(event.actorUserId()).isEqualTo(id);
        assertThat(event.ip()).isEqualTo("203.0.113.2");
    }

    @Test
    void theKeyFormIsLabelledAsTheKey() {
        var request = request("203.0.113.3", "manager");
        request.setParameter("apikey", "chave-errada");
        login.failed(request, new BadCredentialsException("x"));

        assertThat(recorded().actorLabel()).isEqualTo(AuditLog.GLOBAL_KEY);
        assertThat(recorded().toString()).doesNotContain("chave-errada");
    }

    @Test
    void aBlockedIpIsRecordedOncePerWindow() {
        when(access.account(any())).thenReturn(Optional.empty());
        for (int i = 0; i < 5; i++) {
            login.failed(request("203.0.113.4", "x"), new LockedException("x"));
        }
        login.failed(request("203.0.113.5", "x"), new LockedException("x"));   // outro IP conta à parte
        verify(audit, times(2)).record(any());

        now.set(now.get().plus(LoginAttemptLimiter.WINDOW));
        login.failed(request("203.0.113.4", "x"), new LockedException("x"));
        verify(audit, times(3)).record(any());
    }
}
