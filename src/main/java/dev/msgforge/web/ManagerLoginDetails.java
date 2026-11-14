package dev.msgforge.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * Detalhes do POST de login do Manager. O formulário da chave global manda o campo {@code apikey}
 * (o mesmo de sempre, então scripts e testes que entravam com a chave continuam entrando); o de
 * e-mail manda {@code username} e {@code password}. Só vive durante a tentativa: o token autenticado
 * recebe um {@link WebAuthenticationDetails} comum, sem a chave.
 */
class ManagerLoginDetails extends WebAuthenticationDetails {

    private final transient String apiKey;

    ManagerLoginDetails(HttpServletRequest request) {
        super(request);
        this.apiKey = request.getParameter("apikey");
    }

    /** Null quando é login por e-mail e senha. */
    String apiKey() {
        return apiKey;
    }
}
