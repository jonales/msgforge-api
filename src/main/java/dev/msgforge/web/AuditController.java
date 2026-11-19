// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.web;

import dev.msgforge.access.AccessService;
import dev.msgforge.access.Organization;
import dev.msgforge.access.Permission;
import dev.msgforge.audit.AuditLog;
import dev.msgforge.audit.AuditProperties;
import dev.msgforge.audit.AuditLog.Filter;
import dev.msgforge.audit.AuditLog.Query;
import dev.msgforge.web.AuditViews.AuditRow;
import dev.msgforge.web.AuditViews.ScopeOption;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Auditoria do Manager (ADR 0018): logins e ações de pessoas, do mais novo para o mais antigo, com
 * {@link Permission#AUDIT_VIEW}. Cada pessoa vê as organizações em que tem a permissão; o admin da
 * plataforma vê também a plataforma inteira (o padrão para ele), onde entram as tentativas com conta
 * inexistente e as da chave global.
 */
@Controller
@RequestMapping("/manager")
public class AuditController {

    static final int PAGE = 50;

    private final AccessService access;
    private final AuditLog audit;
    private final AuditProperties properties;

    public AuditController(AccessService access, AuditLog audit, AuditProperties properties) {
        this.access = access;
        this.audit = audit;
        this.properties = properties;
    }

    @GetMapping("/audit")
    public String audit(@RequestParam(required = false) UUID org, @RequestParam(required = false) String filter,
                        @RequestParam(required = false) Long before, Model model, HttpServletResponse response) {
        var who = ManagerTopbar.current();
        var allowed = access.organizationsWhere(who, Permission.AUDIT_VIEW);
        if (allowed.isEmpty()) {
            return forbidden(response);
        }
        var platform = who != null && access.isPlatformAdmin(who);
        Organization selected = null;
        if (org != null || !platform) {
            selected = org == null ? allowed.getFirst()
                    : allowed.stream().filter(o -> o.getId().equals(org)).findFirst().orElse(null);
            if (selected == null) {
                return forbidden(response);
            }
        }
        var chosen = Arrays.stream(Filter.values()).filter(f -> f.name().equalsIgnoreCase(filter)).findFirst()
                .orElse(Filter.ALL);
        var page = audit.find(new Query(selected == null ? null : Set.of(selected.getId()), chosen, before, PAGE));
        Map<UUID, String> names = allowed.stream().collect(Collectors.toMap(Organization::getId, Organization::getName));
        var wholePlatform = selected == null;

        var scopes = new ArrayList<ScopeOption>();
        if (platform) {
            scopes.add(new ScopeOption(null, null, wholePlatform));
        }
        for (var o : allowed) {
            scopes.add(new ScopeOption(o.getId(), o.getName(), selected != null && o.getId().equals(selected.getId())));
        }
        model.addAttribute("scopes", scopes);
        model.addAttribute("organizationId", wholePlatform ? null : selected.getId());
        model.addAttribute("organizationName", wholePlatform ? null : selected.getName());
        model.addAttribute("filter", chosen.name());
        model.addAttribute("filters", Arrays.stream(Filter.values()).map(Enum::name).toList());
        model.addAttribute("rows", page.entries().stream().map(e -> AuditRow.of(e, names, wholePlatform)).toList());
        model.addAttribute("wholePlatform", wholePlatform);
        model.addAttribute("older", page.more() ? page.entries().getLast().id() : null);
        model.addAttribute("paged", before != null);
        model.addAttribute("retentionDays", properties.retentionDays());
        model.addAttribute("currentPage", "audit");
        return "manager/audit";
    }

    private static String forbidden(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return "manager/forbidden";
    }
}
