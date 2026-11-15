package dev.msgforge.bot;

import dev.msgforge.bot.BotRules.Action;
import dev.msgforge.bot.BotRules.Session;
import dev.msgforge.bot.BotRules.Status;
import dev.msgforge.bot.ChatModelClient.ModelException;
import dev.msgforge.bot.ChatModelClient.Turn;
import dev.msgforge.bot.InstanceBot.ResumeMode;
import dev.msgforge.bot.InstanceBot.TriggerMode;
import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import dev.msgforge.common.ApiExceptionHandler.InvalidFieldException;
import dev.msgforge.event.GatewayEvent.MessageReceived;
import dev.msgforge.event.OrderedExecutor;
import dev.msgforge.history.HistoryRecorder;
import dev.msgforge.history.MessageHistory;
import dev.msgforge.history.SentBy;
import dev.msgforge.instance.Instance;
import dev.msgforge.instance.InstanceRepository;
import dev.msgforge.message.MessageService;
import dev.msgforge.security.SecretCipher;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O bot com IA de cada instância (ADR 0015). Cada mensagem de texto recebida passa, uma por vez por
 * conversa, pelas {@link BotRules}; quando o bot responde, o texto vai pelo envio normal e entra no
 * histórico com {@code sent_by = BOT}. Uma resposta do atendente (Manager ou celular) pausa o bot na
 * conversa. Falhas (modelo fora, chave errada) só vão para o log: nada de erro para o cliente final.
 */
@Service
public class BotService {

    private static final Logger log = LoggerFactory.getLogger(BotService.class);

    private static final int MAX_PENDING_PER_CHAT = 20;
    private static final int MAX_REPLY_CHARS = 4096;

    public static final SentBy SENT_BY = new SentBy(SentBy.Kind.BOT, null, "Bot");

    private final InstanceBotRepository bots;
    private final BotSessions sessions;
    private final InstanceRepository instances;
    private final MessageHistory history;
    private final HistoryRecorder recorder;
    private final MessageService messages;
    private final ChatModelClient model;
    private final SecretCipher cipher;
    private final BotProperties properties;
    private final OrderedExecutor lanes =
            new OrderedExecutor(Executors.newVirtualThreadPerTaskExecutor(), MAX_PENDING_PER_CHAT);
    /** instância|conversa → quando o bot enviou por último (para reconhecer o eco). */
    private final Map<String, Long> lastBotSend = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    public BotService(InstanceBotRepository bots, BotSessions sessions, InstanceRepository instances,
                      MessageHistory history, HistoryRecorder recorder, MessageService messages,
                      ChatModelClient model, SecretCipher cipher, BotProperties properties) {
        this.bots = bots;
        this.sessions = sessions;
        this.instances = instances;
        this.history = history;
        this.recorder = recorder;
        this.messages = messages;
        this.model = model;
        this.cipher = cipher;
        this.properties = properties;
    }

    /** Para testes: um relógio fixo. */
    void useClock(Clock clock) {
        this.clock = clock;
    }

    // ------------------------------------------------------------ mensagens

    @EventListener
    public void on(MessageReceived event) {
        if (!properties.enabled() || !(event.data().get("key") instanceof Map<?, ?> key)) {
            return;
        }
        var jid = key.get("remoteJid") instanceof String s ? s : null;
        if (jid == null || jid.equals("status@broadcast") || jid.endsWith("@newsletter")) {
            return;
        }
        var lane = event.instanceName() + "|" + jid;
        if (Boolean.TRUE.equals(key.get("fromMe"))) {
            var id = key.get("id") instanceof String s ? s : null;
            lanes.submit(lane, () -> guarded(event.instanceName(), () -> onOwnMessage(event.instanceName(), jid, id)));
            return;
        }
        var text = text(event.data());
        if (text == null || text.isBlank()) {
            return;   // só texto entra no bot
        }
        if (!lanes.submit(lane, () -> guarded(event.instanceName(), () -> onIncoming(event.instanceName(), jid, text)))) {
            log.warn("Bot: fila cheia na instância {}, mensagem sem resposta", event.instanceName());
        }
    }

    private void onIncoming(String instanceName, String jid, String text) {
        var found = instanceAndBot(instanceName);
        if (found.isEmpty()) {
            return;
        }
        var instance = found.get().instance();
        var bot = found.get().bot();
        if (bot.isIgnoreGroups() && jid.endsWith("@g.us")) {
            return;
        }
        var now = clock.instant();
        var decision = BotRules.onMessage(BotRules.Settings.of(bot), sessions.find(instance.getId(), jid).orElse(null),
                text, now);
        if (decision.action() == Action.IGNORE) {
            return;
        }
        sessions.save(instance.getId(), jid, decision.next());
        if (decision.action() == Action.CLOSE) {
            return;
        }
        var reply = model.complete(bot.getBaseUrl(), apiKey(bot), bot.getModel(), context(instance, bot, jid, text));
        if (reply.length() > MAX_REPLY_CHARS) {
            reply = reply.substring(0, MAX_REPLY_CHARS);
        }
        lastBotSend.put(instanceName + "|" + jid, System.currentTimeMillis());
        var delay = bot.getTypingSeconds() > 0 ? bot.getTypingSeconds() * 1000 : null;
        var sent = messages.sendText(instanceName, jid, reply, delay, null);
        lastBotSend.put(instanceName + "|" + jid, System.currentTimeMillis());
        recorder.recordSent(sent.instance(), sent.message(), "conversation", Map.of("conversation", reply), SENT_BY);
    }

    /**
     * Mensagem da própria conta. A do bot (eco) e as enviadas pela Msgforge não contam aqui: o Manager
     * avisa direto ({@link #humanReplied}) e a API não pausa. O resto veio do celular: um atendente.
     */
    private void onOwnMessage(String instanceName, String jid, String id) {
        var sentAt = lastBotSend.get(instanceName + "|" + jid);
        if (sentAt != null && System.currentTimeMillis() - sentAt < properties.echoWindowMs()) {
            return;
        }
        var instance = instances.findByName(instanceName);
        if (instance.isEmpty() || (id != null
                && history.sentBy(instance.get().getId(), jid, java.util.List.of(id)).containsKey(id))) {
            return;
        }
        humanReplied(instanceName, jid);
    }

    /** Um atendente respondeu na conversa: com "pausar quando o atendente assume", o bot sai dela. */
    public void humanReplied(String instanceName, String jid) {
        instanceAndBot(instanceName).ifPresent(found -> {
            var bot = found.bot();
            if (bot.isPauseOnHuman()) {
                sessions.save(found.instance().getId(), jid,
                        BotRules.onHumanReply(bot.getResumeMode(), bot.getPauseMinutes(), clock.instant()));
            }
        });
    }

    /** Memória: as últimas mensagens de texto da conversa (sem a atual) e a mensagem que chegou. */
    private java.util.List<Turn> context(Instance instance, InstanceBot bot, String jid, String text) {
        var turns = new ArrayList<Turn>();
        if (!bot.getSystemPrompt().isBlank()) {
            turns.add(new Turn("system", bot.getSystemPrompt()));
        }
        var recent = new ArrayList<>(history.find(instance.getId(), jid, null, null, 1, bot.getContextMessages() + 1));
        Collections.reverse(recent);   // vem da mais nova para a mais antiga
        var previous = new ArrayList<Turn>();
        for (var stored : recent) {
            var content = text(Map.of("message", stored.message()));
            if (content != null && !content.isBlank()) {
                previous.add(new Turn(stored.key().fromMe() ? "assistant" : "user", content));
            }
        }
        // A mensagem atual pode já estar no histórico (o gravador ouve o mesmo evento): sai daqui e entra no fim
        if (!previous.isEmpty() && previous.getLast().role().equals("user") && previous.getLast().content().equals(text)) {
            previous.removeLast();
        }
        var from = Math.max(0, previous.size() - (bot.getContextMessages() - 1));
        turns.addAll(previous.subList(from, previous.size()));
        turns.add(new Turn("user", text));
        return turns;
    }

    // ------------------------------------------------------------ Manager

    /** O estado do bot numa conversa, para a tela (vazio se a instância não tem bot ligado). */
    public Optional<ChatState> state(String instanceName, String jid) {
        return instanceAndBot(instanceName).map(found -> {
            var session = sessions.find(found.instance().getId(), jid).orElse(null);
            var now = clock.instant();
            if (session == null) {
                return new ChatState(Status.CLOSED, null, false);
            }
            if (session.status() == Status.PAUSED && session.pausedUntil() != null && !now.isBefore(session.pausedUntil())) {
                return new ChatState(Status.ACTIVE, null, false);
            }
            return new ChatState(session.status(), session.pausedUntil(), session.status() == Status.PAUSED);
        });
    }

    /** {@code pausedUntil} nulo numa pausa = até reativar. */
    public record ChatState(Status status, Instant pausedUntil, boolean paused) {}

    /** Pausa à mão: só volta quando alguém reativar. */
    public void pause(String instanceName, String jid) {
        instanceAndBot(instanceName).ifPresent(found -> sessions.save(found.instance().getId(), jid,
                new Session(Status.PAUSED, null, clock.instant())));
    }

    /** Reativa: o bot volta a responder na conversa a partir da próxima mensagem. */
    public void resume(String instanceName, String jid) {
        instanceAndBot(instanceName).ifPresent(found -> sessions.save(found.instance().getId(), jid,
                new Session(Status.ACTIVE, null, clock.instant())));
    }

    public Optional<InstanceBot> find(UUID instanceId) {
        return bots.findById(instanceId);
    }

    /**
     * Guarda a configuração. Valida tudo antes (erro ligado a um campo: {@link InvalidFieldException});
     * a chave nova é cifrada, em branco mantém a guardada.
     */
    @Transactional
    public InstanceBot save(Instance instance, BotForm form) {
        validate(form);
        var bot = bots.findById(instance.getId()).orElseGet(() -> new InstanceBot(instance.getId()));
        bot.apply(form);
        if (form.removeApiKey()) {
            bot.setApiKeyEncrypted(null);
        } else if (form.apiKey() != null && !form.apiKey().isBlank()) {
            if (!cipher.enabled()) {
                throw new InvalidFieldException("apiKey", "noEncryptionKey", "Invalid bot apiKey");
            }
            bot.setApiKeyEncrypted(cipher.encrypt(instance.getId(), form.apiKey().strip()));
        }
        return bots.save(bot);
    }

    /** "Testar agora": manda um texto ao modelo com as instruções e devolve a resposta (nada vai ao WhatsApp). */
    public String test(Instance instance, String text) {
        var bot = bots.findById(instance.getId())
                .orElseThrow(() -> new BadRequestException("Configure o bot antes de testar"));
        var turns = new ArrayList<Turn>();
        if (!bot.getSystemPrompt().isBlank()) {
            turns.add(new Turn("system", bot.getSystemPrompt()));
        }
        turns.add(new Turn("user", text));
        return model.complete(bot.getBaseUrl(), apiKey(bot), bot.getModel(), turns);
    }

    static void validate(BotForm form) {
        try {
            ChatModelClient.endpoint(form.baseUrl());
        } catch (ModelException e) {
            throw new InvalidFieldException("baseUrl", "invalid", "Invalid bot baseUrl");
        }
        if (form.baseUrl().length() > 500) {
            throw new InvalidFieldException("baseUrl", "invalid", "Invalid bot baseUrl");
        }
        if (form.model() == null || form.model().isBlank() || form.model().length() > 120) {
            throw new InvalidFieldException("model", "required", "Invalid bot model");
        }
        if (form.systemPrompt().length() > 8000) {
            throw new InvalidFieldException("systemPrompt", "tooLong", "Invalid bot systemPrompt");
        }
        if (form.triggerMode() != TriggerMode.ALL && BotRules.keywords(form.keywords()).isEmpty()) {
            throw new InvalidFieldException("keywords", "required", "Invalid bot keywords");
        }
        if (form.keywords().length() > 1000 || form.stopKeyword().length() > 60) {
            throw new InvalidFieldException("keywords", "tooLong", "Invalid bot keywords");
        }
        if (form.sessionMinutes() < 0 || form.sessionMinutes() > 10_080) {
            throw new InvalidFieldException("sessionMinutes", "range", "Invalid bot sessionMinutes");
        }
        if (form.resumeMode() == ResumeMode.AUTOMATIC && (form.pauseMinutes() < 1 || form.pauseMinutes() > 10_080)) {
            throw new InvalidFieldException("pauseMinutes", "range", "Invalid bot pauseMinutes");
        }
        if (form.contextMessages() < 1 || form.contextMessages() > 50) {
            throw new InvalidFieldException("contextMessages", "range", "Invalid bot contextMessages");
        }
        if (form.typingSeconds() < 0 || form.typingSeconds() > 10) {
            throw new InvalidFieldException("typingSeconds", "range", "Invalid bot typingSeconds");
        }
    }

    // ------------------------------------------------------------ apoio

    private record Found(Instance instance, InstanceBot bot) {}

    private Optional<Found> instanceAndBot(String instanceName) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        return instances.findByName(instanceName)
                .flatMap(i -> bots.findById(i.getId()).filter(InstanceBot::isEnabled).map(b -> new Found(i, b)));
    }

    private String apiKey(InstanceBot bot) {
        return bot.getApiKeyEncrypted() == null ? null : cipher.decrypt(bot.getInstanceId(), bot.getApiKeyEncrypted());
    }

    /** O texto de uma mensagem no formato do contrato (conversa simples ou texto estendido). */
    @SuppressWarnings("unchecked")
    static String text(Map<String, Object> data) {
        if (!(data.get("message") instanceof Map<?, ?> message)) {
            return null;
        }
        if (message.get("conversation") instanceof String s) {
            return s;
        }
        if (message.get("extendedTextMessage") instanceof Map<?, ?> extended && extended.get("text") instanceof String s) {
            return s;
        }
        return null;
    }

    private static void guarded(String instanceName, Runnable action) {
        try {
            action.run();
        } catch (ModelException e) {
            log.warn("Bot da instância {}: {}", instanceName, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Bot da instância {}: falhou ({})", instanceName, e.getClass().getSimpleName());
        }
    }
}
