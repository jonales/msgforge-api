package dev.msgforge.web;

import dev.msgforge.notification.EmailAddresses.InvalidEmailException;
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
 * Tela Servidor do Manager: o estado do SMTP (ADR 0011) e o "enviar e-mail de teste". A
 * configuração vem do ambiente; a tela só mostra (nunca a senha) e testa.
 */
@Controller
@RequestMapping("/manager/server")
public class ServerController {

    private static final Logger log = LoggerFactory.getLogger(ServerController.class);

    /** Estado do SMTP como a tela mostra. Público: o template lê os campos. */
    public record SmtpView(boolean enabled, String address, boolean starttls, boolean authenticated, String from) {}

    private final NotificationService notifications;

    private final AccessService access;

    public ServerController(NotificationService notifications, AccessService access) {
        this.notifications = notifications;
        this.access = access;
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
                status.starttls(), status.authenticated(), status.from()));
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

    private static String fieldError(RedirectAttributes flash, String typed, String key) {
        flash.addFlashAttribute("typedEmail", typed);
        flash.addFlashAttribute("fieldError", key);
        return "redirect:/manager/server";
    }
}
