// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.web;

import dev.msgforge.access.Permission;
import dev.msgforge.audit.AuditAction;
import dev.msgforge.audit.AuditEntry;
import dev.msgforge.audit.AuditLog;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Modelos da tela de auditoria (ADR 0018). Públicos: o SpEL do Thymeleaf exige. */
public final class AuditViews {

    private AuditViews() {}

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final Set<String> PERMISSIONS =
            Arrays.stream(Permission.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    /**
     * Um trecho do complemento: uma permissão que entrou ({@code +}) ou saiu ({@code -}) do papel, que a
     * tela traduz, ou um texto como veio.
     */
    public record DetailPart(boolean added, String permission, String text) {}

    /**
     * Uma linha da tabela.
     *
     * @param actorKind {@code user}, {@code globalKey} ou {@code unknown} (conta inexistente)
     * @param outcome   nome do {@code AuditOutcome}, também a classe do selo
     * @param organization nome da organização; só preenchido na visão da plataforma inteira
     */
    public record AuditRow(String when, String iso, String action, String outcome, String actorKind, String actor,
                           String target, List<DetailPart> detail, String ip, String organization) {

        static AuditRow of(AuditEntry e, Map<UUID, String> organizationNames, boolean showOrganization) {
            var kind = switch (e.actorLabel()) {
                case AuditLog.GLOBAL_KEY -> "globalKey";
                case AuditLog.UNKNOWN_ACCOUNT -> "unknown";
                default -> "user";
            };
            var organization = !showOrganization || e.organizationId() == null ? null
                    : organizationNames.getOrDefault(e.organizationId(), e.organizationId().toString());
            return new AuditRow(WHEN.format(e.at()), e.at().toString(), e.action().name(), e.outcome().name(), kind,
                    "user".equals(kind) ? e.actorLabel() : null, e.target(), detailOf(e), e.ip(), organization);
        }

        public boolean ok() {
            return "OK".equals(outcome);
        }
    }

    /** O diff de permissões dos papéis vira trechos traduzíveis; o resto sai como texto. */
    static List<DetailPart> detailOf(AuditEntry e) {
        if (e.detail() == null || e.detail().isBlank()) {
            return List.of();
        }
        var role = e.action() == AuditAction.ROLE_CREATED || e.action() == AuditAction.ROLE_UPDATED;
        var tokens = e.detail().split(" ");
        var allPermissions = Arrays.stream(tokens).allMatch(t -> t.length() > 1
                && (t.charAt(0) == '+' || t.charAt(0) == '-') && PERMISSIONS.contains(t.substring(1)));
        if (!role || !allPermissions) {
            return List.of(new DetailPart(false, null, e.detail()));
        }
        return Arrays.stream(tokens).map(t -> new DetailPart(t.charAt(0) == '+', t.substring(1), null)).toList();
    }

    /** Uma opção do seletor de organização; {@code id} null = a plataforma inteira. */
    public record ScopeOption(UUID id, String name, boolean selected) {}
}
