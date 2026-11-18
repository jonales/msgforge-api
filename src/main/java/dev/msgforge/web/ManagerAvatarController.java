// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.web;

import dev.msgforge.access.Permission;
import dev.msgforge.gateway.WhatsAppGateway;
import dev.msgforge.security.PrivateNetworkGuard;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Foto de perfil da conta conectada, para a Visão geral. O CSP do Manager só aceita imagens do
 * próprio servidor, então a foto do CDN do WhatsApp passa por aqui: buscada no servidor (sem
 * redirect, com tempo e tamanho limitados e o filtro de rede da mídia), guardada 10 min por
 * instância e servida como JPEG. Sem foto (ou escondida pela privacidade), 404 e a tela não mostra.
 */
@Controller
@RequestMapping("/manager")
public class ManagerAvatarController {

    private static final Logger log = LoggerFactory.getLogger(ManagerAvatarController.class);
    static final Duration CACHE = Duration.ofMinutes(10);
    static final int MAX_BYTES = 2 * 1024 * 1024;

    private record Cached(byte[] bytes, long at) {}

    private final ManagerAccess guard;
    private final WhatsAppGateway gateway;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final PrivateNetworkGuard network = new PrivateNetworkGuard(true);

    public ManagerAvatarController(ManagerAccess guard, WhatsAppGateway gateway) {
        this.guard = guard;
        this.gateway = gateway;
    }

    @GetMapping("/instances/{name}/avatar")
    public ResponseEntity<byte[]> avatar(@PathVariable String name) {
        var instance = guard.require(name, Permission.INSTANCE_VIEW);
        var owner = instance.getOwnerJid();
        if (owner == null) {
            return ResponseEntity.notFound().build();
        }
        var key = instance.getId() + "|" + owner;
        var cached = cache.get(key);
        if (cached == null || System.currentTimeMillis() - cached.at() > CACHE.toMillis()) {
            cached = new Cached(download(name, instance, owner), System.currentTimeMillis());
            cache.put(key, cached);
        }
        if (cached.bytes() == null) {
            // Conta sem foto: 204 (a <img> falha e ficam as iniciais, sem o 404 que o navegador registra
            // como erro no console) e com cache, porque a lista se atualiza a cada evento e pediria de novo
            return ResponseEntity.noContent().cacheControl(CacheControl.maxAge(CACHE).cachePrivate()).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(CACHE).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .body(cached.bytes());
    }

    /** A foto em bytes, ou null sem foto ou numa falha (só vai para o log, sem a URL). */
    private byte[] download(String name, dev.msgforge.instance.Instance instance, String owner) {
        try {
            var url = gateway.profilePictureUrl(instance, owner);
            if (url == null || !url.startsWith("https://") || network.inspect(url).isPresent()) {
                return null;
            }
            var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    return null;
                }
                var bytes = body.readNBytes(MAX_BYTES + 1);
                return bytes.length > MAX_BYTES || bytes.length < 3 || (bytes[0] & 0xFF) != 0xFF
                        || (bytes[1] & 0xFF) != 0xD8 ? null : bytes;   // só JPEG
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Manager: foto de perfil de {} indisponível ({})", name, e.getClass().getSimpleName());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
