package dev.msgforge.security;

import dev.msgforge.config.MsgforgeProperties;
import dev.msgforge.instance.InstanceRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

    /**
     * Preenchido quando a requisição foi autenticada pelo token de uma instância
     * (e não pela chave global): o nome da instância dona do token.
     */
    public static final String SCOPED_INSTANCE = "msgforge.scopedInstance";

    private static final Set<String> PUBLIC_PREFIXES =
            Set.of("/docs", "/v3/api-docs", "/swagger-ui", "/actuator/health");

    /** Rotas sem instância no path que o token de instância pode usar (restritas a ela). */
    private static final Set<String> SCOPED_ROUTES = Set.of("/instance/fetchInstances");

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
        if (constantTimeEquals(apiKey, properties.authentication().apiKey())) {
            chain.doFilter(request, response);
            return;
        }

        var uri = request.getRequestURI();
        if (SCOPED_ROUTES.contains(uri)) {
            var owner = instances.findByToken(apiKey);
            if (owner.isPresent()) {
                request.setAttribute(SCOPED_INSTANCE, owner.get().getName());
                chain.doFilter(request, response);
            } else {
                unauthorized(response);
            }
            return;
        }

        // Token de instância: só vale para a instância do path (/{grupo}/{acao}/{instance})
        var instanceName = instanceFromPath(uri);
        var allowed = instanceName != null && instances.findByName(instanceName)
                .map(i -> constantTimeEquals(apiKey, i.getToken()))
                .orElse(false);
        if (allowed) {
            request.setAttribute(SCOPED_INSTANCE, instanceName);
            chain.doFilter(request, response);
        } else {
            unauthorized(response);
        }
    }

    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
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
