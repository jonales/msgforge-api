package dev.msgforge.web;

import dev.msgforge.access.AccessService;
import dev.msgforge.access.AccessService.LoginResult;
import dev.msgforge.access.ManagerPrincipal;
import dev.msgforge.config.MsgforgeProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.ExceptionMappingAuthenticationFailureHandler;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

/**
 * Spring Security <b>só</b> para o Manager ({@code /manager/**}, ADR 0005).
 *
 * <p>A API continua autenticada pelo {@code ApiKeyFilter} (header {@code apikey}, contrato
 * público): a segunda cadeia libera tudo, sem sessão, sem CSRF e sem os headers extras, para a
 * resposta da API não mudar por causa do Manager.
 */
@Configuration
public class ManagerSecurityConfig {

    static final String ROLE = "MANAGER";

    @Bean
    @Order(1)
    SecurityFilterChain managerChain(HttpSecurity http, AuthenticationProvider managerAuthentication,
                                     LoginAttemptLimiter limiter, AccessService access) throws Exception {
        http.securityMatcher("/manager", "/manager/**")
                .addFilterBefore(new ActiveSessionFilter(access), AuthorizationFilter.class)
                .authenticationProvider(managerAuthentication)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/manager/login", "/manager/manager.css", "/manager/manager.js", "/manager/favicon.svg",
                                "/manager/vendor/**").permitAll()
                        .anyRequest().hasRole(ROLE))
                .formLogin(form -> form
                        .loginPage("/manager/login")
                        .loginProcessingUrl("/manager/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .authenticationDetailsSource(ManagerLoginDetails::new)
                        .successHandler((request, response, authentication) -> {
                            limiter.succeeded(request.getRemoteAddr());
                            if (authentication.getPrincipal() instanceof ManagerPrincipal who) {
                                access.recordLogin(who);
                            }
                            response.sendRedirect(request.getContextPath() + "/manager");
                        })
                        .failureHandler(failureHandler(limiter)))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint()))
                .logout(logout -> logout
                        .logoutUrl("/manager/logout")
                        .logoutSuccessUrl("/manager/login?logout"))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; "
                                + "frame-ancestors 'none'; form-action 'self'; base-uri 'none'")));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .headers(headers -> headers.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());
        return http.build();
    }

    /**
     * Sem sessão: página de login. Exceto no polling do htmx, que seguiria o redirect e encaixaria
     * a página de login inteira dentro do trecho atualizado; para ele, 401 + HX-Redirect. O stream
     * SSE (ADR 0013) recebe só 401: a página volta ao polling, que faz o redirect.
     */
    private static AuthenticationEntryPoint entryPoint() {
        var login = new LoginUrlAuthenticationEntryPoint("/manager/login");
        return (request, response, ex) -> {
            var accept = request.getHeader("Accept");
            if (accept != null && accept.contains("text/event-stream")) {
                response.setStatus(401);
            } else if ("true".equals(request.getHeader("HX-Request"))) {
                response.setStatus(401);
                response.setHeader("HX-Redirect", request.getContextPath() + "/manager/login");
            } else {
                login.commence(request, response, ex);
            }
        };
    }

    /**
     * Conta a falha aqui (uma vez por requisição), e não no provider: o Boot registra o provider
     * também no AuthenticationManager pai, e numa chave errada ele é chamado duas vezes.
     */
    private static AuthenticationFailureHandler failureHandler(LoginAttemptLimiter limiter) {
        var redirect = new ExceptionMappingAuthenticationFailureHandler();
        redirect.setExceptionMappings(Map.of(
                LockedException.class.getName(), "/manager/login?blocked",
                DisabledException.class.getName(), "/manager/login?disabled"));
        redirect.setDefaultFailureUrl("/manager/login?error");
        return (request, response, exception) -> {
            if (exception instanceof BadCredentialsException) {
                limiter.failed(request.getRemoteAddr());
            }
            redirect.onAuthenticationFailure(request, response, exception);
        };
    }

    /**
     * Dois jeitos de entrar (ADR 0010): e-mail e senha de um usuário ativo, ou a chave global
     * (AUTHENTICATION_API_KEY, comparada em tempo constante), que entra como admin da plataforma no
     * primeiro acesso e enquanto {@code MANAGER_KEY_LOGIN} for true. Um IP com
     * {@link LoginAttemptLimiter#MAX_FAILURES} falhas na janela fica bloqueado, nos dois jeitos.
     * Nenhuma falha é contada aqui (veja {@link #failureHandler}).
     */
    @Bean
    AuthenticationProvider managerAuthentication(MsgforgeProperties properties, LoginAttemptLimiter limiter,
                                                 AccessService access) {
        var expected = properties.authentication().apiKey().getBytes(StandardCharsets.UTF_8);
        return new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication authentication) {
                var details = authentication.getDetails() instanceof WebAuthenticationDetails web ? web : null;
                var ip = details == null ? null : details.getRemoteAddress();
                if (limiter.blocked(ip)) {
                    throw new LockedException("Muitas tentativas de login; tente mais tarde");
                }
                ManagerPrincipal who;
                if (details instanceof ManagerLoginDetails login && login.apiKey() != null) {
                    var given = login.apiKey().getBytes(StandardCharsets.UTF_8);
                    if (!MessageDigest.isEqual(expected, given) || !access.keyLoginAllowed()) {
                        throw new BadCredentialsException("API key inválida");   // contada no failure handler
                    }
                    who = ManagerPrincipal.GLOBAL_KEY;
                } else {
                    who = switch (access.login(authentication.getName(), String.valueOf(authentication.getCredentials()))) {
                        case LoginResult.Success success -> success.principal();
                        case LoginResult.WrongCredentials wrong ->
                                throw new BadCredentialsException("E-mail ou senha inválidos");
                        case LoginResult.Disabled disabled -> throw new DisabledException("Usuário bloqueado");
                    };
                }
                var result = UsernamePasswordAuthenticationToken.authenticated(who, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + ROLE)));
                // Sem a chave digitada: a sessão guarda só o IP e o id da sessão
                result.setDetails(details == null ? null
                        : new WebAuthenticationDetails(details.getRemoteAddress(), details.getSessionId()));
                return result;
            }

            @Override
            public boolean supports(Class<?> type) {
                return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);
            }
        };
    }
}
