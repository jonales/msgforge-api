package dev.msgforge.security;

import dev.msgforge.config.MsgforgeProperties;
import dev.msgforge.instance.InstanceRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Mesmo modelo da Evolution: header {@code apikey} com a chave global
 * (acesso total) ou o token da instância (acesso só às rotas dela).
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_PREFIXES =
            Set.of("/docs", "/v3/api-docs", "/swagger-ui", "/actuator/health");

    private final MsgforgeProperties properties;
    private final InstanceRepository instances;

    public ApiKeyFilter(MsgforgeProperties properties, InstanceRepository instances) {
        this.properties = properties;
        this.instances = instances;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = request.getRequestURI();
        return path.equals("/") || PUBLIC_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        var apiKey = request.getHeader("apikey");
        if (apiKey == null || apiKey.isBlank()) {
            unauthorized(response);
            return;
        }
        if (apiKey.equals(properties.authentication().apiKey())) {
            chain.doFilter(request, response);
            return;
        }
        // Token de instância: só vale para a instância do path (/{grupo}/{acao}/{instance})
        var instanceName = instanceFromPath(request.getRequestURI());
        var allowed = instanceName != null && instances.findByName(instanceName)
                .map(i -> i.getToken().equals(apiKey))
                .orElse(false);
        if (allowed) {
            chain.doFilter(request, response);
        } else {
            unauthorized(response);
        }
    }

    private static String instanceFromPath(String uri) {
        var parts = uri.split("/");
        return parts.length >= 4 ? parts[3] : null;
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"response\":{\"message\":\"Unauthorized\"}}");
    }
}
