// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Tema do Manager: Sistema (padrão, segue o {@code prefers-color-scheme}), Claro ou Escuro. A
 * escolha fica num cookie, que vale também para quem entra com a chave global. As telas leem
 * {@code ${@managerTheme.attribute()}} e o servidor põe o {@code data-theme} já no {@code <html>}:
 * o navegador pinta o tema certo de primeira, sem script.
 */
@Component("managerTheme")
public class ManagerTheme {

    static final String COOKIE = "msgforge_theme";

    /** As opções, na ordem em que o botão de tema passa por elas. {@code SYSTEM} não guarda cookie nem põe atributo. */
    public enum Choice {
        SYSTEM, LIGHT, DARK;

        /** Valor do cookie e do {@code data-theme}; null no Sistema. */
        public String attribute() {
            return this == SYSTEM ? null : name().toLowerCase(Locale.ROOT);
        }

        /** O próximo do botão de tema: Sistema, Claro, Escuro e de volta ao Sistema. */
        public Choice next() {
            return values()[(ordinal() + 1) % values().length];
        }

        /** O valor do formulário ({@code system}, {@code light}, {@code dark}). */
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Cookie ausente, adulterado ou de uma versão antiga vira Sistema. */
        static Choice parse(String value) {
            if (value != null) {
                for (var choice : values()) {
                    if (choice.value().equals(value)) {
                        return choice;
                    }
                }
            }
            return SYSTEM;
        }
    }

    /** O {@code data-theme} da página atual: "light", "dark" ou null (segue o sistema). */
    public String attribute() {
        return choice().attribute();
    }

    /** A escolha guardada (o botão mostra a atual e grava a próxima). */
    public Choice choice() {
        var request = request();
        return request == null ? Choice.SYSTEM : read(request);
    }

    /**
     * Para onde voltar depois de trocar o tema: a própria página, se ela foi aberta por GET.
     * Uma página desenhada em resposta a um POST (formulário com erro) volta para o início.
     */
    public String back() {
        var request = request();
        if (request == null || !"GET".equals(request.getMethod())) {
            return "/manager";
        }
        var path = request.getRequestURI().substring(request.getContextPath().length());
        var query = request.getQueryString();
        return safeBack(query == null ? path : path + "?" + query);
    }

    static Choice read(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE.equals(cookie.getName())) {
                    return Choice.parse(cookie.getValue());
                }
            }
        }
        return Choice.SYSTEM;
    }

    /**
     * Só caminhos do próprio Manager: nada de {@code //host}, barra invertida ou quebra de linha,
     * que fariam do redirecionamento um link para fora.
     */
    static String safeBack(String back) {
        if (back == null || !(back.equals("/manager") || back.startsWith("/manager/") || back.startsWith("/manager?"))
                || back.contains("//") || back.contains("\\") || back.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            return "/manager";
        }
        return back;
    }

    private static HttpServletRequest request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }
}
