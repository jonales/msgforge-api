package dev.msgforge.web;

import dev.msgforge.access.AccessService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Tela de login do Manager (ADR 0010). O POST é do Spring Security ({@link ManagerSecurityConfig}).
 * O caminho principal é e-mail e senha; a chave global aparece como alternativa enquanto puder
 * entrar e é o caminho do primeiro acesso, quando ainda não há usuários.
 */
@Controller
public class LoginController {

    private final AccessService access;

    public LoginController(AccessService access) {
        this.access = access;
    }

    @GetMapping("/manager/login")
    public String login(Model model) {
        model.addAttribute("firstAccess", !access.hasUsers());
        model.addAttribute("keyLogin", access.keyLoginAllowed());
        return "manager/login";
    }
}
