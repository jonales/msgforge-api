package dev.msgforge.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Troca do tema pelo menu da conta: um POST comum (com CSRF), que grava o cookie e volta para a
 * página onde a pessoa estava. Sem script: funciona com o CSP só {@code 'self'}.
 */
@Controller
public class ThemeController {

    private static final Duration KEEP = Duration.ofDays(365);

    @PostMapping("/manager/theme")
    public String change(@RequestParam(defaultValue = "system") String theme,
                         @RequestParam(defaultValue = "/manager") String back,
                         HttpServletRequest request, HttpServletResponse response) {
        var choice = ManagerTheme.Choice.parse(theme);
        var cookie = ResponseCookie.from(ManagerTheme.COOKIE, choice == ManagerTheme.Choice.SYSTEM ? "" : choice.value())
                .path(request.getContextPath() + "/manager")
                .maxAge(choice == ManagerTheme.Choice.SYSTEM ? Duration.ZERO : KEEP)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return "redirect:" + ManagerTheme.safeBack(back);
    }
}
