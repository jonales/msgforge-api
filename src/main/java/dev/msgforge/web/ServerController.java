// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.web;

import dev.msgforge.common.ApiExceptionHandler.InvalidFieldException;
import dev.msgforge.notification.EmailAddresses.InvalidEmailException;
import dev.msgforge.notification.EmailSender;
import dev.msgforge.notification.SmtpAdmin;
import dev.msgforge.security.SecretCipher;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import dev.msgforge.notification.Notification;
import dev.msgforge.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import dev.msgforge.access.AccessService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Tela Servidor do Manager: o SMTP (ADR 0011 e 0019) e o "enviar e-mail de teste". O SMTP se configura,
 * testa e volta ao ambiente por aqui; a senha nunca volta para a tela.
 */
@Controller
@RequestMapping("/manager/server")
public class ServerController {

    private static final Logger log = LoggerFactory.getLogger(ServerController.class);

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    /**
     * Estado do SMTP como a tela mostra. Público: o template lê os campos.
     *
     * @param source  {@code DATABASE} (definido aqui), {@code ENVIRONMENT} (SMTP_*) ou {@code NONE}
     * @param problem por que a configuração daqui não pode ser usada, ou null
     */
    public record SmtpView(boolean enabled, String address, boolean starttls, boolean authenticated, String from,
                           String source, String updatedAt, String updatedBy, String problem) {

        public boolean fromDatabase() {
            return "DATABASE".equals(source);
        }
    }

    /** O que preenche o formulário: o que está em uso ou o que a pessoa digitou (nunca a senha). */
    public record SmtpForm(String host, String port, String username, String from, boolean starttls,
                           String timeoutMs, boolean hasPassword) {}

    private final NotificationService notifications;
    private final AccessService access;
    private final SmtpAdmin smtp;
    private final SecretCipher secrets;

    public ServerController(NotificationService notifications, AccessService access, SmtpAdmin smtp,
                            SecretCipher secrets) {
        this.notifications = notifications;
        this.access = access;
        this.smtp = smtp;
        this.secrets = secrets;
    }

    /** Configuração do servidor (SMTP, e-mail de teste): só a chave global ou um admin da plataforma. */
    private boolean allowed() {
        var who = ManagerTopbar.current();
        return who != null && access.isPlatformAdmin(who);
    }

    @GetMapping
    public String server(Model model, HttpServletResponse response) {
        if (!allowed()) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return "manager/forbidden";
        }
        model.addAttribute("currentPage", "server");
        var status = notifications.emailStatus();
        model.addAttribute("smtp", new SmtpView(status.enabled(),
                status.host() == null ? null : status.host() + ":" + status.port(),
                status.starttls(), status.authenticated(), status.from(), status.source().name(),
                status.updatedAt() == null ? null : WHEN.format(status.updatedAt()),
                status.updatedBy(), status.problem()));
        if (!model.containsAttribute("smtpForm")) {
            var inUse = status.source() != EmailSender.Source.NONE;
            model.addAttribute("smtpForm", new SmtpForm(inUse ? status.host() : "",
                    String.valueOf(inUse ? status.port() : 587), inUse ? status.username() : "",
                    inUse ? status.from() : "", !inUse || status.starttls(),
                    String.valueOf(inUse ? status.timeoutMs() : 10000),
                    status.source() == EmailSender.Source.DATABASE && status.authenticated()));
        }
        if (!model.containsAttribute("smtpErrors")) {
            model.addAttribute("smtpErrors", Map.of());
        }
        model.addAttribute("secretsEnabled", secrets.enabled());
        if (!model.containsAttribute("typedEmail")) {
            model.addAttribute("typedEmail", "");
        }
        return "manager/server";
    }

    @PostMapping("/test-email")
    public String testEmail(@RequestParam(defaultValue = "") String email, RedirectAttributes flash,
                            HttpServletResponse response) {
        if (!allowed()) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return "manager/forbidden";
        }
        var typed = email.strip();
        if (typed.isEmpty()) {
            return fieldError(flash, typed, "manager.server.test.required");
        }
        try {
            switch (notifications.send(Notification.test(typed))) {
                case QUEUED -> flash.addFlashAttribute("sentTo", typed);
                case DISABLED -> flash.addFlashAttribute("error", "manager.server.test.disabled");
            }
        } catch (InvalidEmailException e) {
            return fieldError(flash, typed, "manager.server.test.invalid");
        } catch (RuntimeException e) {
            log.warn("E-mail de teste não entrou na fila: {}", e.getMessage(), e);
            flash.addFlashAttribute("typedEmail", typed);
            flash.addFlashAttribute("error", "manager.server.test.failed");
        }
        return "redirect:/manager/server";
    }

    /** Salva ou testa o SMTP digitado ({@code action} = {@code save} ou {@code test}). */
    @PostMapping("/smtp")
    public String saveSmtp(@RequestParam(defaultValue = "save") String action,
                           @RequestParam(defaultValue = "") String host, @RequestParam(defaultValue = "") String port,
                           @RequestParam(defaultValue = "") String username,
                           @RequestParam(defaultValue = "") String password,
                           @RequestParam(defaultValue = "") String from,
                           @RequestParam(defaultValue = "false") boolean starttls,
                           @RequestParam(defaultValue = "") String timeoutMs,
                           RedirectAttributes flash, HttpServletResponse response) {
        if (!allowed()) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return "manager/forbidden";
        }
        var form = new SmtpAdmin.Form(host, port, username, password, from, starttls, timeoutMs);
        var status = notifications.emailStatus();
        var typed = new SmtpForm(host.strip(), port.strip(), username.strip(), from.strip(), starttls, timeoutMs.strip(),
                status.source() == EmailSender.Source.DATABASE && status.authenticated());
        try {
            if ("test".equals(action)) {
                flash.addFlashAttribute("smtpForm", typed);
                flash.addFlashAttribute("smtpTest", smtp.test(form));
            } else {
                smtp.save(ManagerTopbar.current(), form);
                flash.addFlashAttribute("success", "manager.server.smtp.saved");
            }
        } catch (InvalidFieldException e) {
            flash.addFlashAttribute("smtpForm", typed);
            flash.addFlashAttribute("smtpErrors",
                    Map.of(e.field(), "manager.server.smtp.error." + e.field() + "." + e.reason()));
        }
        return "redirect:/manager/server";
    }

    /** Apaga a configuração daqui: volta a valer a do ambiente (SMTP_*), se houver. */
    @PostMapping("/smtp/remove")
    public String removeSmtp(RedirectAttributes flash, HttpServletResponse response) {
        if (!allowed()) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return "manager/forbidden";
        }
        if (smtp.remove(ManagerTopbar.current())) {
            flash.addFlashAttribute("success", "manager.server.smtp.removed");
        }
        return "redirect:/manager/server";
    }

    private static String fieldError(RedirectAttributes flash, String typed, String key) {
        flash.addFlashAttribute("typedEmail", typed);
        flash.addFlashAttribute("fieldError", key);
        return "redirect:/manager/server";
    }
}
