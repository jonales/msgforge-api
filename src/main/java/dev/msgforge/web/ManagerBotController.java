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

    @GetMapping("/instances/{name}/integrations/bot")
    public String page(@PathVariable String name, Model model) {
        var check = guard.current();
        var instance = guard.require(check, name, Permission.ACCOUNT_CONFIGURE);
        model.addAttribute("can", ManagerViews.PanelAccess.of(ManagerAccess.on(check, instance)));
        model.addAttribute("instance", ManagerViews.InstanceDetail.of(instance, instances.stateOf(instance)));
        if (!model.containsAttribute("bot")) {
            model.addAttribute("bot", bots.find(instance.getId()).map(BotView::of).orElse(BotView.NEW));
        }
        model.addAttribute("secretsEnabled", instances.secretsEnabled());
        return "manager/instance-bot";
    }

    @PostMapping("/instances/{name}/integrations/bot")
    public String save(@PathVariable String name,
                       @RequestParam(defaultValue = "false") boolean enabled,
                       @RequestParam(defaultValue = "") String baseUrl,
                       @RequestParam(defaultValue = "") String model,
                       @RequestParam(required = false) String apiKey,
                       @RequestParam(defaultValue = "false") boolean removeApiKey,
                       @RequestParam(defaultValue = "") String systemPrompt,
                       @RequestParam(defaultValue = "ALL") TriggerMode triggerMode,
                       @RequestParam(defaultValue = "") String keywords,
                       @RequestParam(defaultValue = "") String stopKeyword,
                       @RequestParam(defaultValue = "0") int sessionMinutes,
                       @RequestParam(defaultValue = "false") boolean ignoreGroups,
                       @RequestParam(defaultValue = "false") boolean pauseOnHuman,
                       @RequestParam(defaultValue = "AUTOMATIC") ResumeMode resumeMode,
                       @RequestParam(defaultValue = "30") int pauseMinutes,
                       @RequestParam(defaultValue = "10") int contextMessages,
                       @RequestParam(defaultValue = "0") int typingSeconds,
                       RedirectAttributes flash) {
        var instance = guard.require(name, Permission.ACCOUNT_CONFIGURE);
        var form = new BotForm(enabled, baseUrl.strip(), model.strip(), apiKey, removeApiKey, systemPrompt.strip(),
                triggerMode, keywords.strip(), stopKeyword.strip(), sessionMinutes, ignoreGroups, pauseOnHuman,
                resumeMode, pauseMinutes, contextMessages, typingSeconds);
        try {
            bots.save(instance, form);
        } catch (InvalidFieldException e) {
            var stored = bots.find(instance.getId()).map(InstanceBot::hasApiKey).orElse(false);
            flash.addFlashAttribute("bot", BotView.typed(form, stored));
            flash.addFlashAttribute("fieldErrors", Map.of("bot." + e.field(),
                    "manager.bot.error." + e.field() + "." + e.reason()));
            return "redirect:" + page(name);
        }
        flash.addFlashAttribute("saved", "bot");
        return "redirect:" + page(name);
    }

    /** "Testar agora": a resposta do modelo para um texto, sem passar pelo WhatsApp (htmx). */
    @PostMapping("/instances/{name}/integrations/bot/test")
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

    private static String page(String name) {
        return "/manager/instances/" + UriUtils.encodePathSegment(name, StandardCharsets.UTF_8) + "/integrations/bot";
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
