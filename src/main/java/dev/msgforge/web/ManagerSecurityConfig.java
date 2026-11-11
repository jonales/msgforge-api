package dev.msgforge.web;

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
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.ExceptionMappingAuthenticationFailureHandler;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

/**
 * Spring Security <b>só</b> para o Manager ({@code /manager/**}, ADR 0005).
 *
 * <p>A API continua autenticada pelo {@code ApiKeyFilter} (header {@code apikey}, contrato da
 * Evolution): a segunda cadeia libera tudo, sem sessão, sem CSRF e sem os headers extras, para a
 * resposta da API não mudar por causa do Manager.
 */
@Configuration
public class ManagerSecurityConfig {

    static final String ROLE = "MANAGER";

    @Bean
    @Order(1)
    SecurityFilterChain managerChain(HttpSecurity http, AuthenticationProvider managerAuthentication,
                                     LoginAttemptLimiter limiter) throws Exception {
        http.securityMatcher("/manager", "/manager/**")
                .authenticationProvider(managerAuthentication)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/manager/login", "/manager/manager.css", "/manager/vendor/**").permitAll()
                        .anyRequest().hasRole(ROLE))
                .formLogin(form -> form
                        .loginPage("/manager/login")
                        .loginProcessingUrl("/manager/login")
                        .usernameParameter("username")
                        .passwordParameter("apikey")
                        .successHandler((request, response, authentication) -> {
                            limiter.succeeded(request.getRemoteAddr());
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
     * a página de login inteira dentro do trecho atualizado; para ele, 401 + HX-Redirect.
     */
    private static AuthenticationEntryPoint entryPoint() {
        var login = new LoginUrlAuthenticationEntryPoint("/manager/login");
        return (request, response, ex) -> {
            if ("true".equals(request.getHeader("HX-Request"))) {
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
        redirect.setExceptionMappings(Map.of(LockedException.class.getName(), "/manager/login?blocked"));
        redirect.setDefaultFailureUrl("/manager/login?error");
        return (request, response, exception) -> {
            if (exception instanceof BadCredentialsException) {
                limiter.failed(request.getRemoteAddr());
            }
            redirect.onAuthenticationFailure(request, response, exception);
        };
    }

    /**
     * O login do Manager é a chave global (AUTHENTICATION_API_KEY), comparada em tempo constante.
     * Um IP com {@link LoginAttemptLimiter#MAX_FAILURES} falhas na janela fica bloqueado.
     */
    @Bean
    AuthenticationProvider managerAuthentication(MsgforgeProperties properties, LoginAttemptLimiter limiter) {
        var expected = properties.authentication().apiKey().getBytes(StandardCharsets.UTF_8);
        return new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication authentication) {
                var ip = authentication.getDetails() instanceof WebAuthenticationDetails details
                        ? details.getRemoteAddress() : null;
                if (limiter.blocked(ip)) {
                    throw new LockedException("Muitas tentativas de login; tente mais tarde");
                }
                var given = String.valueOf(authentication.getCredentials()).getBytes(StandardCharsets.UTF_8);
                if (!MessageDigest.isEqual(expected, given)) {
                    throw new BadCredentialsException("API key inválida");   // contada no failure handler
                }
                return UsernamePasswordAuthenticationToken.authenticated("manager", null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + ROLE)));
            }

            @Override
            public boolean supports(Class<?> type) {
                return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);
            }
        };
    }
}
