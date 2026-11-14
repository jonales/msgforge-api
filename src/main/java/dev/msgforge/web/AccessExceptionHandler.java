package dev.msgforge.web;

import dev.msgforge.access.AccessAdminService.AccessRuleException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Regra recusada fora de um formulário (papel que não existe mais, por exemplo) vira mensagem em
 * flash, e não o JSON da API. Sem a permissão (o serviço confere de novo, mesmo num POST montado à
 * mão), a tela "Sem acesso" com 403; sem este tratador, o {@code ApiExceptionHandler} daria 500.
 */
@ControllerAdvice(assignableTypes = AccessController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class AccessExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    String denied(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return "manager/forbidden";
    }

    @ExceptionHandler(AccessRuleException.class)
    String refused(AccessRuleException ex, HttpServletRequest request, RedirectAttributes flash) {
        flash.addFlashAttribute("error", ex.getMessage());
        var org = request.getParameter("org");
        var base = request.getRequestURI().contains("/manager/roles") ? "/manager/roles" : "/manager/users";
        return "redirect:" + base + (org != null && org.matches("[0-9a-fA-F-]{36}") ? "?org=" + org : "");
    }
}
