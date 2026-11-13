package dev.msgforge.web;

import dev.msgforge.common.ApiExceptionHandler.NotFoundException;
import dev.msgforge.instance.InstanceService;
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
    private final InstanceService instances;

    public ManagerStreamController(ManagerStreams streams, InstanceService instances) {
        this.streams = streams;
        this.instances = instances;
    }

    /** Lista de instâncias: evento {@code table}. */
    @GetMapping("/instances")
    public ResponseEntity<SseEmitter> list(HttpSession session) {
        return open(ManagerStreams.LIST, session);
    }

    /** Painel da instância (estado e QR): evento {@code live}. */
    @GetMapping("/instances/{name}")
    public ResponseEntity<SseEmitter> instance(@PathVariable String name, HttpSession session) {
        return exists(name) ? open(ManagerStreams.instanceChannel(name), session) : notFound();
    }

    /** Conversas: eventos {@code chats} e {@code conversation:{jid}} (a conversa aberta, página 1). */
    @GetMapping("/instances/{name}/messages")
    public ResponseEntity<SseEmitter> messages(@PathVariable String name, HttpSession session) {
        return exists(name) ? open(ManagerStreams.messagesChannel(name), session) : notFound();
    }

    /** Sem passar pelo handler de erros da API, que responderia JSON a quem pediu text/event-stream. */
    private boolean exists(String name) {
        try {
            instances.get(name);
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }

    private static ResponseEntity<SseEmitter> notFound() {
        return ResponseEntity.notFound().build();
    }

    /** Aba fechada no meio do stream: não há a quem responder (nem erro para registrar). */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void disconnected() {
        // nada: o ManagerStreams já tirou a conexão da lista
    }

    private ResponseEntity<SseEmitter> open(String channel, HttpSession session) {
        var emitter = streams.open(channel, session);
        if (emitter == null) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        // Proxies (nginx) seguram a resposta em buffer e o evento só chegaria no fim; ver docs/configuracao.md
        return ResponseEntity.ok().header("X-Accel-Buffering", "no").body(emitter);
    }
}
