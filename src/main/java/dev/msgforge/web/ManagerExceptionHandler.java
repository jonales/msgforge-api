package dev.msgforge.web;

import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import dev.msgforge.common.ApiExceptionHandler.NotFoundException;
import dev.msgforge.gateway.WhatsAppGateway.GatewayException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Erros das telas viram mensagem em flash e redirecionamento, em vez do JSON do
 * {@code ApiExceptionHandler}, que continua valendo só para a API.
 */
@ControllerAdvice(assignableTypes = {ManagerController.class, NewInstanceController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
class ManagerExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ManagerExceptionHandler.class);

    /**
     * Instância que não existe ou que a pessoa não vê ({@link ManagerAccess}): a mesma resposta nos
     * dois casos, para não revelar que existe.
     */
    @ExceptionHandler(NotFoundException.class)
    String notFound(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return "manager/not-found";
    }

    /** Vê a instância, mas o papel não inclui a ação (o POST montado à mão também cai aqui). */
    @ExceptionHandler(AccessDeniedException.class)
    String denied(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return "manager/forbidden";
    }

    @ExceptionHandler({BadRequestException.class, GatewayException.class, MissingServletRequestParameterException.class})
    String failed(Exception ex, HttpServletRequest request, RedirectAttributes flash) {
        flash.addFlashAttribute("errorText", ex.getMessage());
        return "redirect:" + backTo(request);
    }

    @ExceptionHandler(Exception.class)
    String unexpected(Exception ex, HttpServletRequest request, RedirectAttributes flash) {
        log.error("Erro não tratado no Manager", ex);
        flash.addFlashAttribute("error", "manager.flash.unexpected");
        return "redirect:" + backTo(request);
    }

    /** Volta para a tela da instância (POST /manager/instances/{nome}/acao) ou para a lista. */
    private static String backTo(HttpServletRequest request) {
        var parts = request.getRequestURI().split("/");
        // ["", "manager", "instances", "{nome}", "{acao}"]
        if (parts.length >= 5 && "instances".equals(parts[2])) {
            return "/manager/instances/" + parts[3];
        }
        return "/manager";
    }
}
