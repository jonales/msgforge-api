// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Troca do tema pelo botão da lateral: um POST comum (com CSRF), que grava o cookie, volta para a
 * página onde a pessoa estava e deixa o aviso "Tema claro definido" (flash {@code themeChanged}). Sem
 * script: funciona com o CSP só {@code 'self'}.
 */
@Controller
public class ThemeController {

    private static final Duration KEEP = Duration.ofDays(365);

    @PostMapping("/manager/theme")
    public String change(@RequestParam(defaultValue = "system") String theme,
                         @RequestParam(defaultValue = "/manager") String back,
                         HttpServletRequest request, HttpServletResponse response,
                         RedirectAttributes flash) {
        var choice = ManagerTheme.Choice.parse(theme);
        var cookie = ResponseCookie.from(ManagerTheme.COOKIE, choice == ManagerTheme.Choice.SYSTEM ? "" : choice.value())
                .path(request.getContextPath() + "/manager")
                .maxAge(choice == ManagerTheme.Choice.SYSTEM ? Duration.ZERO : KEEP)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        flash.addFlashAttribute("themeChanged", choice.value());
        return "redirect:" + ManagerTheme.safeBack(back);
    }
}
