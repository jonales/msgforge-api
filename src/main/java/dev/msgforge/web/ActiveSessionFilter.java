package dev.msgforge.web;

import dev.msgforge.access.AccessService;
import dev.msgforge.access.ManagerPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Usuário bloqueado (ou apagado) perde a sessão na próxima requisição, sem esperar ela expirar: a
 * sessão é invalidada e a requisição segue como anônima, então o entry point leva ao login (ou
 * responde 401 + HX-Redirect no polling do htmx).
 *
 * <p>Não é um {@code @Component} de propósito: o Boot registraria o filtro em todas as rotas,
 * inclusive na API. Só a cadeia do Manager o usa.
 */
class ActiveSessionFilter extends OncePerRequestFilter {

    private final AccessService access;

    ActiveSessionFilter(AccessService access) {
        this.access = access;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof ManagerPrincipal who
                && !access.isActive(who)) {
            var session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }
}
