package dev.msgforge.web;

import dev.msgforge.common.ApiExceptionHandler.NotFoundException;
import dev.msgforge.access.Permission;
import java.util.Optional;
import org.springframework.security.access.AccessDeniedException;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Tempo real do Manager (ADR 0013): streams SSE com os mesmos trechos de HTML que o polling busca.
 * Ficam sob {@code /manager/**}, então exigem a sessão do Manager como qualquer tela. Se o stream
 * não abrir (limite por sessão, proxy, rede), a página continua no polling.
 */
@Controller
@RequestMapping(path = "/manager/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public class ManagerStreamController {

    private final ManagerStreams streams;
    private final ManagerAccess guard;

    public ManagerStreamController(ManagerStreams streams, ManagerAccess guard) {
        this.streams = streams;
        this.guard = guard;
    }

    /** Lista de instâncias: evento {@code table}, só com as instâncias que a pessoa vê. */
    @GetMapping("/instances")
    public ResponseEntity<SseEmitter> list(HttpSession session) {
        return open(ManagerStreams.LIST, session);
    }

    /** Painel da instância (estado e, com "conectar", o QR): evento {@code live}. Pede "ver status". */
    @GetMapping("/instances/{name}")
    public ResponseEntity<SseEmitter> instance(@PathVariable String name, HttpSession session) {
        return allowed(name, Permission.INSTANCE_VIEW)
                .orElseGet(() -> open(ManagerStreams.instanceChannel(name), session));
    }

    /** Conversas: eventos {@code chats} e {@code conversation:{jid}} (a conversa aberta, página 1). */
    @GetMapping("/instances/{name}/messages")
    public ResponseEntity<SseEmitter> messages(@PathVariable String name, HttpSession session) {
        return allowed(name, Permission.CHAT_VIEW)
                .orElseGet(() -> open(ManagerStreams.messagesChannel(name), session));
    }

    /**
     * A recusa, se houver: 404 para a instância que não existe ou que a pessoa não vê, 403 sem a
     * permissão. Sem passar pelo handler de erros da API, que responderia JSON a quem pediu
     * text/event-stream.
     */
    private Optional<ResponseEntity<SseEmitter>> allowed(String name, Permission permission) {
        try {
            guard.require(name, permission);
            return Optional.empty();
        } catch (NotFoundException e) {
            return Optional.of(ResponseEntity.notFound().build());
        } catch (AccessDeniedException e) {
            return Optional.of(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }
    }

    /** Aba fechada no meio do stream: não há a quem responder (nem erro para registrar). */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void disconnected() {
        // nada: o ManagerStreams já tirou a conexão da lista
    }

    private ResponseEntity<SseEmitter> open(String channel, HttpSession session) {
        var emitter = streams.open(channel, session, ManagerTopbar.current());
        if (emitter == null) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        // Proxies (nginx) seguram a resposta em buffer e o evento só chegaria no fim; ver docs/configuracao.md
        return ResponseEntity.ok().header("X-Accel-Buffering", "no").body(emitter);
    }
}
