package dev.msgforge.web;

import dev.msgforge.access.AccessCheck;
import dev.msgforge.access.AccessService;
import dev.msgforge.access.ManagerPrincipal;
import dev.msgforge.access.Permission;
import dev.msgforge.common.ApiExceptionHandler.NotFoundException;
import dev.msgforge.web.ManagerViews.InstanceRow;
import java.util.HashMap;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Conexões SSE abertas pelo Manager (ADR 0013) e o envio dos trechos de HTML.
 *
 * <p>Cada conexão assina um canal: a lista ({@code instances}), o painel de uma instância
 * ({@code instance:{nome}}) ou as conversas dela ({@code messages:{nome}}). Mudanças chegam pelo
 * {@link ManagerBus}; as de um mesmo canal são agrupadas por {@link #DEBOUNCE}. Sem assinante, nada
 * é renderizado.
 *
 * <p>Permissões (ADR 0010, fatia 5): cada assinante leva quem abriu o stream, e cada envio relê
 * as permissões dele ({@link AccessService#check}, uma leitura por pessoa e por envio). A lista
 * depende de quem vê: o trecho é renderizado <b>uma vez por conteúdo distinto</b> (as mesmas linhas visíveis e o
 * mesmo botão de criar), e quem vê o mesmo recebe o mesmo HTML. Nada é compartilhado entre
 * conteúdos diferentes, então a linha de uma pessoa não chega a outra. No painel, o trecho sai em
 * até duas versões (com e sem o QR, pela permissão de conectar); nas conversas, uma só, para quem
 * tem "ver conversas". Quem perdeu a permissão (ou foi bloqueado) tem o stream fechado no envio
 * seguinte.
 *
 * <p>Robustez: comentário de batida a cada {@link #HEARTBEAT} (proxies não derrubam a conexão
 * ociosa, e uma conexão morta ou uma sessão encerrada é descoberta), no máximo
 * {@link #MAX_PER_SESSION} conexões por sessão, e a conexão fecha sozinha em {@link #TIMEOUT}
 * (o navegador reconecta e passa de novo pela sessão). No desligamento, os streams fecham antes
 * do desligamento gracioso do servidor, que senão esperaria por eles.
 */
@Component
public class ManagerStreams implements SmartLifecycle {

    static final Duration HEARTBEAT = Duration.ofSeconds(25);
    static final Duration TIMEOUT = Duration.ofMinutes(15);
    static final Duration DEBOUNCE = Duration.ofMillis(250);
    /** Abaixo do limite de 6 conexões HTTP/1.1 por origem do navegador: sobra conexão para o resto. */
    static final int MAX_PER_SESSION = 4;
    /** Num pico (ex.: sincronização do histórico), as conversas além destas esperam o polling. */
    static final int MAX_CONVERSATIONS_PER_FLUSH = 20;

    static final String LIST = "instances";

    private static final Logger log = LoggerFactory.getLogger(ManagerStreams.class);

    /** Uma aba com o stream aberto. */
    private record Subscriber(String channel, SseEmitter emitter, HttpSession session, String sessionId,
                              ManagerPrincipal who) {}

    private final ManagerScreens screens;
    private final FragmentRenderer renderer;
    private final AccessService access;
    private final ConcurrentHashMap<String, Set<Subscriber>> channels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> perSession = new ConcurrentHashMap<>();
    /** Canal → conversas tocadas desde o último envio; a chave existe enquanto um envio está agendado. */
    private final ConcurrentHashMap<String, Set<String>> pending = new ConcurrentHashMap<>();
    /** Criados no {@code start()} e desligados no {@code stop()}: o ciclo de vida pode reiniciar. */
    private volatile ScheduledExecutorService timer;
    private volatile ExecutorService workers;

    public ManagerStreams(ManagerBus bus, ManagerScreens screens, FragmentRenderer renderer,
                          AccessService access) {
        this.access = access;
        this.screens = screens;
        this.renderer = renderer;
        bus.subscribe(this::on);
    }

    static String instanceChannel(String name) {
        return "instance:" + name;
    }

    static String messagesChannel(String name) {
        return "messages:" + name;
    }

    /** Nome do evento SSE da conversa aberta (o {@code sse-swap} do trecho "conversation"). */
    public static String conversationEvent(String jid) {
        return "conversation:" + jid;
    }

    // ------------------------------------------------------------ assinatura

    /**
     * Abre o stream do canal e já envia o trecho atual (cobre o que mudou entre a página e o
     * stream, e o que se perdeu numa reconexão). {@code null} quando a sessão passou do limite.
     */
    public SseEmitter open(String channel, HttpSession session, ManagerPrincipal who) {
        if (!reserve(session.getId())) {
            // Abas que a sessão deixou para trás (navegação) só seriam descobertas na próxima batida
            probe(session.getId());
            if (!reserve(session.getId())) {
                return null;
            }
        }
        var emitter = new SseEmitter(TIMEOUT.toMillis());
        var subscriber = new Subscriber(channel, emitter, session, session.getId(), who);
        channels.computeIfAbsent(channel, _ -> ConcurrentHashMap.newKeySet()).add(subscriber);
        emitter.onCompletion(() -> remove(subscriber));
        emitter.onTimeout(() -> {
            remove(subscriber);
            emitter.complete();   // sem isto, o timeout vira exceção num response já em text/event-stream
        });
        emitter.onError(_ -> remove(subscriber));
        workers.execute(() -> snapshot(subscriber));
        return emitter;
    }

    private boolean reserve(String sessionId) {
        var count = perSession.computeIfAbsent(sessionId, _ -> new AtomicInteger());
        if (count.incrementAndGet() > MAX_PER_SESSION) {
            count.decrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * Escreve nas conexões da sessão para descobrir as que o navegador já fechou: o servidor só
     * percebe a aba fechada ao escrever. A primeira escrita pode ir para o buffer do sistema; a
     * segunda, depois da recusa do outro lado, falha.
     */
    private void probe(String sessionId) {
        for (var set : channels.values()) {
            for (var subscriber : List.copyOf(set)) {
                if (!subscriber.sessionId().equals(sessionId)) {
                    continue;
                }
                for (int i = 0; i < 2; i++) {
                    try {
                        subscriber.emitter().send(SseEmitter.event().comment("probe"));
                    } catch (IOException | IllegalStateException e) {
                        remove(subscriber);
                        break;
                    }
                }
            }
        }
    }

    /** Conexões abertas no canal (para testes e diagnóstico). */
    int subscribers(String channel) {
        var set = channels.get(channel);
        return set == null ? 0 : set.size();
    }

    private void remove(Subscriber subscriber) {
        var set = channels.get(subscriber.channel());
        if (set != null && set.remove(subscriber)) {
            channels.computeIfPresent(subscriber.channel(), (_, s) -> s.isEmpty() ? null : s);
            perSession.computeIfPresent(subscriber.sessionId(),
                    (_, n) -> n.decrementAndGet() <= 0 ? null : n);
        }
    }

    // ------------------------------------------------------------ mudanças

    private void on(ManagerUpdate update) {
        var name = update.instanceName();
        switch (update.change()) {
            case CONNECTION -> {
                schedule(LIST, null);
                schedule(instanceChannel(name), null);
            }
            case QR -> schedule(instanceChannel(name), null);
            case MESSAGES -> schedule(messagesChannel(name), update.chatJid());
        }
    }

    private void schedule(String channel, String jid) {
        if (!channels.containsKey(channel)) {
            return;   // ninguém olhando
        }
        pending.compute(channel, (_, jids) -> {
            if (jids == null) {
                jids = new HashSet<>();
                timer.schedule(() -> workers.execute(() -> flush(channel)), DEBOUNCE.toMillis(), TimeUnit.MILLISECONDS);
            }
            if (jid != null) {
                jids.add(jid);
            }
            return jids;
        });
    }

    private void flush(String channel) {
        var jids = pending.remove(channel);
        var targets = channels.get(channel);
        if (jids == null || targets == null || targets.isEmpty()) {
            return;
        }
        var started = System.nanoTime();
        try {
            var renders = deliver(channel, jids, List.copyOf(targets));
            if (log.isDebugEnabled()) {
                log.debug("Manager: canal {}: {} assinante(s), {} renderização(ões), {} ms", channel, targets.size(),
                        renders, (System.nanoTime() - started) / 1_000_000);
            }
        } catch (NotFoundException e) {
            log.debug("Manager: canal {} sem instância ({}); fechando os streams", channel, e.getMessage());
            List.copyOf(targets).forEach(this::close);
        } catch (RuntimeException e) {
            log.warn("Manager: não renderizou o trecho do canal {}: {}", channel, e.getMessage());
        }
    }

    private void snapshot(Subscriber subscriber) {
        try {
            deliver(subscriber.channel(), Set.of(), List.of(subscriber));
        } catch (NotFoundException e) {
            close(subscriber);
        } catch (RuntimeException e) {
            log.warn("Manager: não renderizou o trecho inicial do canal {}: {}", subscriber.channel(), e.getMessage());
        }
    }

    private void close(Subscriber subscriber) {
        remove(subscriber);
        subscriber.emitter().complete();
    }

    // ------------------------------------------------------------ trechos

    /** Um evento SSE: o nome é o {@code sse-swap} do trecho na página. */
    record Fragment(String event, String html) {}

    /** O que define o trecho da lista para uma pessoa: duas pessoas com a mesma visão dividem o HTML. */
    private record ListView(List<InstanceRow> rows, boolean canCreate) {}

    /**
     * Envia o trecho do canal a cada assinante, conforme as permissões dele agora, e devolve quantas
     * vezes renderizou. Quem não pode mais ver o canal tem o stream fechado.
     */
    private int deliver(String channel, Set<String> jids, List<Subscriber> targets) {
        // Uma leitura das permissões por pessoa e por envio (várias abas da mesma pessoa dividem)
        var checks = new HashMap<ManagerPrincipal, AccessCheck>();
        java.util.function.Function<Subscriber, AccessCheck> checkOf = s -> s.who() == null ? AccessCheck.NOBODY
                : checks.computeIfAbsent(s.who(), access::check);
        if (LIST.equals(channel)) {
            var all = screens.allRows();
            var rendered = new HashMap<ListView, Fragment>();
            for (var subscriber : targets) {
                var check = checkOf.apply(subscriber);
                if (!check.active()) {
                    close(subscriber);
                    continue;
                }
                var view = new ListView(ManagerScreens.visible(all, check),
                        check.canSomewhere(Permission.INSTANCE_CREATE));
                send(subscriber, rendered.computeIfAbsent(view, v -> new Fragment("table",
                        renderer.render("manager/instances", "table",
                                Map.of("instances", v.rows(), "canCreate", v.canCreate())))));
            }
            return rendered.size();
        }
        var name = channel.substring(channel.indexOf(':') + 1);
        var instance = screens.instance(name);
        var needed = channel.startsWith("instance:") ? Permission.INSTANCE_VIEW : Permission.CHAT_VIEW;
        var allowed = new ArrayList<Subscriber>();
        var withCode = new HashMap<Subscriber, Boolean>();
        for (var subscriber : targets) {
            var check = checkOf.apply(subscriber);
            if (!check.can(Permission.INSTANCE_VIEW, instance) || !check.can(needed, instance)) {
                close(subscriber);
                continue;
            }
            allowed.add(subscriber);
            withCode.put(subscriber, check.can(Permission.INSTANCE_CONNECT, instance));
        }
        if (allowed.isEmpty()) {
            return 0;
        }
        if (channel.startsWith("instance:")) {
            var rendered = new HashMap<Boolean, Fragment>();
            for (var subscriber : allowed) {
                send(subscriber, rendered.computeIfAbsent(withCode.get(subscriber), code -> new Fragment("live",
                        renderer.render("manager/instance", "live", Map.of("live", screens.live(name, code))))));
            }
            return rendered.size();
        }
        var fragments = conversations(name, jids);
        for (var subscriber : allowed) {
            fragments.forEach(f -> send(subscriber, f));
        }
        return fragments.size();
    }

    private List<Fragment> conversations(String name, Set<String> jids) {
        var out = new ArrayList<Fragment>();
        out.add(new Fragment("chats", renderer.render("manager/messages", "chats",
                Map.of("instanceName", name, "chats", screens.chatRows(name)))));
        jids.stream().filter(ManagerStreams::safeEventName).limit(MAX_CONVERSATIONS_PER_FLUSH)
                .forEach(jid -> out.add(new Fragment(conversationEvent(jid), renderer.render("manager/messages",
                        "conversation", Map.of("instanceName", name, "conversation", screens.conversation(name, jid, 1))))));
        return out;
    }

    /** O nome do evento vai numa linha do protocolo SSE. */
    private static boolean safeEventName(String jid) {
        return !jid.isBlank() && jid.indexOf('\n') < 0 && jid.indexOf('\r') < 0 && jid.indexOf(',') < 0;
    }

    private void send(Subscriber subscriber, Fragment fragment) {
        try {
            subscriber.emitter().send(SseEmitter.event().name(fragment.event()).data(fragment.html()));
        } catch (IOException | IllegalStateException e) {
            // Aba fechada ou conexão caída: o container avisa o emitter; aqui só sai da lista
            remove(subscriber);
        }
    }

    // ------------------------------------------------------------ batida

    private void heartbeat() {
        for (var set : channels.values()) {
            for (var subscriber : List.copyOf(set)) {
                if (!sessionAlive(subscriber.session())) {
                    remove(subscriber);
                    subscriber.emitter().complete();   // logout ou sessão expirada: o stream acaba junto
                    continue;
                }
                try {
                    subscriber.emitter().send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException e) {
                    remove(subscriber);
                }
            }
        }
    }

    private static boolean sessionAlive(HttpSession session) {
        try {
            session.getLastAccessedTime();
            return true;
        } catch (IllegalStateException invalidated) {
            return false;
        }
    }

    // ------------------------------------------------------------ ciclo de vida

    private volatile boolean running;

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        // Executores novos a cada start: o Spring pode parar e religar o contexto (os contextos de
        // teste em cache são pausados e retomados), e um executor desligado recusa tarefas para sempre
        workers = Executors.newVirtualThreadPerTaskExecutor();
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .name("manager-sse-timer").daemon().factory());
        timer.scheduleAtFixedRate(() -> workers.execute(this::heartbeat),
                HEARTBEAT.toMillis(), HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS);
        running = true;
    }

    /** Fase padrão (a mais alta): para antes do desligamento gracioso do servidor web. */
    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        timer.shutdownNow();
        channels.values().forEach(set -> List.copyOf(set).forEach(s -> s.emitter().complete()));
        channels.clear();
        pending.clear();
        perSession.clear();
        workers.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
