package dev.msgforge.web;

import dev.msgforge.access.Permission;
import dev.msgforge.bot.BotForm;
import dev.msgforge.bot.BotService;
import dev.msgforge.bot.ChatModelClient.ModelException;
import dev.msgforge.bot.InstanceBot;
import dev.msgforge.bot.InstanceBot.ResumeMode;
import dev.msgforge.bot.InstanceBot.TriggerMode;
import dev.msgforge.common.ApiExceptionHandler.InvalidFieldException;
import dev.msgforge.instance.Instance;
import dev.msgforge.instance.InstanceService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;

/**
 * Bot com IA no Manager (ADR 0015): a configuração na tela Integrações da instância (pede "configurar a
 * conta") e, na conversa, pausar e reativar o bot (pede "enviar mensagens").
 */
@Controller
@RequestMapping("/manager")
public class ManagerBotController {

    private final ManagerAccess guard;
    private final InstanceService instances;
    private final BotService bots;
    private final ManagerScreens screens;

    public ManagerBotController(ManagerAccess guard, InstanceService instances, BotService bots,
                                ManagerScreens screens) {
        this.screens = screens;
        this.guard = guard;
        this.instances = instances;
        this.bots = bots;
    }

    /** As abas da tela do bot, na ordem; cada uma salva só o que é dela. */
    static final java.util.List<String> TABS = java.util.List.of("model", "trigger", "handoff", "advanced", "test");

    @GetMapping("/instances/{name}/integrations/bot")
    public String home(@PathVariable String name) {
        guard.require(name, Permission.ACCOUNT_CONFIGURE);
        return "redirect:" + page(name, "model");
    }

    @GetMapping("/instances/{name}/integrations/bot/{tab}")
    public String page(@PathVariable String name, @PathVariable String tab, Model model) {
        if (!TABS.contains(tab)) {
            throw new dev.msgforge.common.ApiExceptionHandler.NotFoundException("Unknown bot tab: " + tab);
        }
        var check = guard.current();
        var instance = guard.require(check, name, Permission.ACCOUNT_CONFIGURE);
        var saved = bots.find(instance.getId()).map(BotView::of).orElse(BotView.NEW);
        if (!saved.configured() && !"model".equals(tab)) {
            return "redirect:" + page(name, "model");   // sem modelo, as outras abas não têm o que salvar
        }
        model.addAttribute("can", ManagerViews.PanelAccess.of(ManagerAccess.on(check, instance)));
        model.addAttribute("instance", ManagerViews.InstanceDetail.of(instance, instances.stateOf(instance)));
        model.addAttribute("tab", tab);
        model.addAttribute("tabs", TABS);
        model.addAttribute("savedBot", saved);
        if (!model.containsAttribute("bot")) {
            model.addAttribute("bot", saved);
        }
        model.addAttribute("secretsEnabled", instances.secretsEnabled());
        return "manager/instance-bot";
    }

    /**
     * Salva uma aba e só ela: os campos das outras ficam como estão guardados (um interruptor ausente do
     * POST de outra aba não pode desligar nada).
     */
    @PostMapping("/instances/{name}/integrations/bot/{tab}")
    public String save(@PathVariable String name, @PathVariable String tab,
                       @RequestParam Map<String, String> params, RedirectAttributes flash) {
        if (!TABS.contains(tab) || "test".equals(tab)) {
            throw new dev.msgforge.common.ApiExceptionHandler.NotFoundException("Unknown bot tab: " + tab);
        }
        var instance = guard.require(name, Permission.ACCOUNT_CONFIGURE);
        var s = bots.find(instance.getId()).map(BotView::of).orElse(BotView.NEW);
        var form = new BotForm(
                "model".equals(tab) ? on(params, "enabled") : s.enabled(),
                "model".equals(tab) ? text(params, "baseUrl") : s.baseUrl(),
                "model".equals(tab) ? text(params, "model") : s.model(),
                "model".equals(tab) ? params.get("apiKey") : null,
                "model".equals(tab) && on(params, "removeApiKey"),
                "model".equals(tab) ? text(params, "systemPrompt") : s.systemPrompt(),
                "trigger".equals(tab) ? enumOf(TriggerMode.class, params.get("triggerMode"), TriggerMode.ALL)
                        : TriggerMode.valueOf(s.triggerMode()),
                "trigger".equals(tab) ? text(params, "keywords") : s.keywords(),
                "trigger".equals(tab) ? text(params, "stopKeyword") : s.stopKeyword(),
                "trigger".equals(tab) ? number(params, "sessionMinutes", -1) : s.sessionMinutes(),
                "trigger".equals(tab) ? on(params, "ignoreGroups") : s.ignoreGroups(),
                "handoff".equals(tab) ? on(params, "pauseOnHuman") : s.pauseOnHuman(),
                "handoff".equals(tab) ? enumOf(ResumeMode.class, params.get("resumeMode"), ResumeMode.AUTOMATIC)
                        : ResumeMode.valueOf(s.resumeMode()),
                "handoff".equals(tab) ? number(params, "pauseMinutes", -1) : s.pauseMinutes(),
                "advanced".equals(tab) ? number(params, "contextMessages", -1) : s.contextMessages(),
                "advanced".equals(tab) ? number(params, "typingSeconds", -1) : s.typingSeconds());
        try {
            bots.save(instance, form);
        } catch (InvalidFieldException e) {
            flash.addFlashAttribute("bot", BotView.typed(form, s.hasApiKey()));
            flash.addFlashAttribute("fieldErrors", Map.of("bot." + e.field(),
                    "manager.bot.error." + e.field() + "." + e.reason()));
            return "redirect:" + page(name, tab);
        }
        flash.addFlashAttribute("saved", tab);
        return "redirect:" + page(name, tab);
    }

    private static boolean on(Map<String, String> params, String field) {
        return "true".equals(params.get(field));
    }

    private static String text(Map<String, String> params, String field) {
        var value = params.get(field);
        return value == null ? "" : value.strip();
    }

    /** Número inválido vira {@code invalid}, que a validação recusa perto do campo. */
    private static int number(Map<String, String> params, String field, int invalid) {
        try {
            return Integer.parseInt(text(params, field));
        } catch (NumberFormatException e) {
            return invalid;
        }
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value, E fallback) {
        try {
            return value == null ? fallback : Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** "Testar agora": a resposta do modelo para um texto, sem passar pelo WhatsApp (htmx). */
    @PostMapping("/instances/{name}/integrations/bot/test/ask")
    public String test(@PathVariable String name, @RequestParam(defaultValue = "") String text, Model model) {
        var instance = guard.require(name, Permission.ACCOUNT_CONFIGURE);
        if (text.isBlank()) {
            model.addAttribute("testError", "manager.bot.test.empty");
        } else {
            try {
                model.addAttribute("testReply", bots.test(instance, text.strip()));
            } catch (ModelException e) {
                model.addAttribute("testError", "manager.bot.test.failed");
                model.addAttribute("testDetail", e.getMessage());
            }
        }
        return "manager/instance-bot :: testResult";
    }

    /** Pausar (até reativar) ou reativar o bot numa conversa; volta para a conversa. */
    @PostMapping("/instances/{name}/messages/bot")
    public String chatBot(@PathVariable String name, @RequestParam String jid, @RequestParam String action,
                          @org.springframework.web.bind.annotation.RequestHeader(value = "HX-Request", required = false)
                          String htmx, Model model) {
        guard.require(name, Permission.CHAT_SEND);
        if ("pause".equals(action)) {
            bots.pause(name, jid);
        } else if ("resume".equals(action)) {
            bots.resume(name, jid);
        }
        if (htmx != null) {   // pelo htmx: a conversa com o estado novo do bot
            model.addAttribute("instanceName", name);
            model.addAttribute("conversation", screens.conversation(name, jid, 1));
            return "manager/messages :: conversation";
        }
        return "redirect:/manager/instances/" + UriUtils.encodePathSegment(name, StandardCharsets.UTF_8)
                + "/messages?jid=" + UriUtils.encodeQueryParam(jid, StandardCharsets.UTF_8);
    }

    private static String page(String name, String tab) {
        return "/manager/instances/" + UriUtils.encodePathSegment(name, StandardCharsets.UTF_8) + "/integrations/bot/"
                + tab;
    }

    /** O bot na tela. Nunca carrega a chave: só se há uma guardada. */
    public record BotView(boolean configured, boolean enabled, String baseUrl, String model, boolean hasApiKey,
                          String systemPrompt, String triggerMode, String keywords, String stopKeyword,
                          int sessionMinutes, boolean ignoreGroups, boolean pauseOnHuman, String resumeMode,
                          int pauseMinutes, int contextMessages, int typingSeconds) {

        static final BotView NEW = new BotView(false, false, "https://api.openai.com/v1", "", false, "", "ALL", "",
                "", 0, true, true, "AUTOMATIC", 30, 10, 0);

        static BotView of(InstanceBot bot) {
            return new BotView(true, bot.isEnabled(), bot.getBaseUrl(), bot.getModel(), bot.hasApiKey(),
                    bot.getSystemPrompt(), bot.getTriggerMode().name(), bot.getKeywords(), bot.getStopKeyword(),
                    bot.getSessionMinutes(), bot.isIgnoreGroups(), bot.isPauseOnHuman(), bot.getResumeMode().name(),
                    bot.getPauseMinutes(), bot.getContextMessages(), bot.getTypingSeconds());
        }

        static BotView typed(BotForm form, boolean hasApiKey) {
            return new BotView(true, form.enabled(), form.baseUrl(), form.model(), hasApiKey && !form.removeApiKey(),
                    form.systemPrompt(), form.triggerMode().name(), form.keywords(), form.stopKeyword(),
                    form.sessionMinutes(), form.ignoreGroups(), form.pauseOnHuman(), form.resumeMode().name(),
                    form.pauseMinutes(), form.contextMessages(), form.typingSeconds());
        }

        /** O que a aba mostra embaixo do título (estado guardado). */
        public String tabStateKey(String tab) {
            return switch (tab) {
                case "model" -> enabled ? "manager.bot.tabState.on" : "manager.bot.tabState.off";
                case "trigger" -> "manager.bot.tabState.trigger." + triggerMode;
                case "handoff" -> !pauseOnHuman ? "manager.bot.tabState.noPause"
                        : "MANUAL".equals(resumeMode) ? "manager.bot.tabState.manual" : "manager.bot.tabState.minutes";
                case "advanced" -> "manager.bot.tabState.context";
                default -> "manager.bot.tabState.test";
            };
        }

        public java.util.List<String> triggers() {
            return java.util.List.of("ALL", "KEYWORD", "EXACT");
        }

        public java.util.List<String> resumeModes() {
            return java.util.List.of("AUTOMATIC", "MANUAL");
        }
    }

    static boolean botOn(BotService bots, Instance instance) {
        return bots.find(instance.getId()).map(InstanceBot::isEnabled).orElse(false);
    }
}
