// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Raiz pública: diz que a API está no ar e onde ficam o Manager e a documentação. Os links são absolutos, montados
 * a partir da requisição (atrás de proxy, o host e o esquema vêm do {@code X-Forwarded-*} só dos
 * {@code TRUSTED_PROXIES}).
 */
@RestController
public class RootController {

    @GetMapping("/")
    public Map<String, Object> root() {
        var base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        var body = new LinkedHashMap<String, Object>();
        body.put("status", 200);
        body.put("message", "Welcome to Msgforge API, it is working!");
        body.put("version", "0.1.0");
        body.put("manager", base + "/manager");
        body.put("documentation", base + "/docs");
        return body;
    }
}
