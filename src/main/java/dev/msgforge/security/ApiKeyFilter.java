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
 * Autenticação da API: header {@code apikey} com a chave global
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
            Set.of("/docs", "/v3/api-docs", "/swagger-ui", "/actuator/health",
                    "/manager");   // o Manager tem login próprio (ManagerSecurityConfig)

    /** Rotas sem instância no path que o token de instância pode usar (restritas a ela). */
    private static final Set<String> SCOPED_ROUTES = Set.of("/instance/fetchInstances");

    private final MsgforgeProperties properties;
    private final InstanceRepository instances;
    private final ApiKeyAttemptLimiter limiter;

    public ApiKeyFilter(MsgforgeProperties properties, InstanceRepository instances, ApiKeyAttemptLimiter limiter) {
        this.properties = properties;
        this.instances = instances;
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = request.getRequestURI();
        return path.equals("/") || PUBLIC_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        var ip = request.getRemoteAddr();
        if (limiter.blocked(ip)) {
            tooManyAttempts(response, limiter.retryAfterSeconds(ip));
            return;
        }
        if (!authenticate(request)) {
            limiter.failed(ip);
            unauthorized(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Confere a chave e, para token de instância, marca a instância dona ({@link #SCOPED_INSTANCE}). */
    private boolean authenticate(HttpServletRequest request) {
        var apiKey = request.getHeader("apikey");
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        if (constantTimeEquals(apiKey, properties.authentication().apiKey())) {
            return true;
        }

        var uri = request.getRequestURI();
        if (SCOPED_ROUTES.contains(uri)) {
            var owner = instances.findByToken(apiKey);
            owner.ifPresent(instance -> request.setAttribute(SCOPED_INSTANCE, instance.getName()));
            return owner.isPresent();
        }

        // Token de instância: só vale para a instância do path (/{grupo}/{acao}/{instance})
        var instanceName = instanceFromPath(uri);
        var allowed = instanceName != null && instances.findByName(instanceName)
                .map(i -> constantTimeEquals(apiKey, i.getToken()))
                .orElse(false);
        if (allowed) {
            request.setAttribute(SCOPED_INSTANCE, instanceName);
        }
        return allowed;
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

    /** 429 no formato de erro do contrato, com Retry-After. */
    private static void tooManyAttempts(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":429,\"error\":\"Too Many Requests\",\"response\":{\"message\":"
                + "[\"Too many invalid api keys from this address; try again later\"]}}");
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"response\":{\"message\":\"Unauthorized\"}}");
    }
}
