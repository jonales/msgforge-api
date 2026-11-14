package dev.msgforge.web;

import dev.msgforge.event.EventType;
import dev.msgforge.instance.InstanceDtos.CreateInstanceRequest;
import dev.msgforge.common.ApiExceptionHandler.InvalidFieldException;
import dev.msgforge.instance.HistorySync;
import dev.msgforge.instance.InstanceService;
import dev.msgforge.instance.InstanceSettings;
import dev.msgforge.message.MessageService;
import dev.msgforge.web.ManagerViews.InstanceDetail;
import dev.msgforge.web.ManagerViews.InstanceRow;
import dev.msgforge.web.ManagerViews.Live;
import dev.msgforge.web.ManagerViews.ProxyView;
import dev.msgforge.web.ManagerViews.SettingsView;
import dev.msgforge.web.ManagerViews.WebhookView;
import dev.msgforge.webhook.WebhookDtos.WebhookConfig;
import dev.msgforge.webhook.WebhookService;
import java.util.Arrays;
import java.util.List;
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
 * Manager (ADR 0005): telas server-side em Thymeleaf. Formulários seguem Post/Redirect/Get com a
 * mensagem em flash; erros dos serviços viram flash pelo {@link ManagerExceptionHandler}.
 */
@Controller
@RequestMapping("/manager")
public class ManagerController {

    static final List<String> EVENT_NAMES = Arrays.stream(EventType.values()).map(Enum::name).toList();

    static final List<String> HISTORY_OPTIONS = Arrays.stream(dev.msgforge.instance.HistorySync.values())
            .map(Enum::name).toList();

    private final InstanceService instances;
    private final WebhookService webhooks;
    private final MessageService messages;
    private final QrCodeCache qrCodes;
    private final ManagerScreens screens;
    private final dev.msgforge.history.HistoryRecorder history;

    public ManagerController(InstanceService instances, WebhookService webhooks, MessageService messages,
                             QrCodeCache qrCodes, ManagerScreens screens,
                             dev.msgforge.history.HistoryRecorder history) {
        this.instances = instances;
        this.webhooks = webhooks;
        this.messages = messages;
        this.qrCodes = qrCodes;
        this.screens = screens;
        this.history = history;
    }

    @GetMapping("/login")
    public String login() {
        return "manager/login";
    }

    // ------------------------------------------------------------ lista

    /** A busca e o filtro da lista só aparecem acima disto: com poucas instâncias, atrapalham. */
    static final int SEARCH_THRESHOLD = 8;

    /** Canais e integrações sem backend: aparecem como "Em breve" (PRODUCT.md), sem link nem botão. */
    static final List<String> SOON_CHANNELS = List.of("cloudApi");
    static final List<String> SOON_INTEGRATIONS = List.of("typebot", "chatwoot", "openai", "dify", "flowise", "n8n");

    @GetMapping({"", "/"})
    public String list(Model model) {
        var rows = rows();
        model.addAttribute("instances", rows);
        model.addAttribute("showSearch", rows.size() > SEARCH_THRESHOLD);
        model.addAttribute("soonChannels", SOON_CHANNELS);
        model.addAttribute("soonIntegrations", SOON_INTEGRATIONS);
        return "manager/instances";
    }

    /** Trecho da tabela: o polling do htmx busca este; o SSE (ADR 0013) envia o mesmo trecho. */
    @GetMapping("/instances/table")
    public String table(Model model) {
        model.addAttribute("instances", rows());
        return "manager/instances :: table";
    }

    /** Criação direta, sem os passos (a tela usa a guiada, {@link NewInstanceController}). */
    @PostMapping("/instances")
    public String create(@RequestParam String instanceName, @RequestParam(required = false) String number,
                         @RequestParam(required = false) String historySync, RedirectAttributes flash) {
        var name = instanceName.trim();
        var created = instances.create(new CreateInstanceRequest(name, null, false, blankToNull(number), null, null));
        instances.setHistorySync(name, dev.msgforge.instance.HistorySync.parse(historySync));
        flash.addFlashAttribute("success", "manager.flash.created");
        // O token só aparece uma vez, logo após a criação
        flash.addFlashAttribute("createdToken", created.hash());
        return redirectTo(name);
    }

    // ------------------------------------------------------------ detalhe

    @GetMapping("/instances/{name}")
    public String detail(@PathVariable String name, Model model) {
        var instance = instances.get(name);
        model.addAttribute("instance", InstanceDetail.of(instance, instances.stateOf(instance)));
        model.addAttribute("live", live(name));
        model.addAttribute("webhook", webhooks.find(name).map(WebhookView::of).orElse(WebhookView.EMPTY));
        model.addAttribute("eventNames", EVENT_NAMES);
        model.addAttribute("historyOptions", HISTORY_OPTIONS);
        if (!model.containsAttribute("settings")) {
            model.addAttribute("settings", SettingsView.of(instance));
        }
        if (!model.containsAttribute("proxy")) {
            model.addAttribute("proxy", ProxyView.of(instance));
        }
        // O proxy fica recolhido, salvo logo depois de salvá-lo ou com erro nele
        var errors = model.getAttribute("fieldErrors");
        model.addAttribute("proxyOpen", model.containsAttribute("proxySaved")
                || errors instanceof java.util.Map<?, ?> map && map.keySet().stream()
                        .anyMatch(key -> key.toString().startsWith("proxy.")));
        model.addAttribute("behaviors", SettingsView.BEHAVIORS);
        model.addAttribute("proxyProtocols", ProxyView.PROTOCOLS);
        model.addAttribute("secretsEnabled", instances.secretsEnabled());
        return "manager/instance";
    }

    /** Estado + QR, atualizado pelo htmx enquanto a instância não está conectada. */
    @GetMapping("/instances/{name}/live")
    public String liveFragment(@PathVariable String name, Model model) {
        model.addAttribute("live", live(name));
        return "manager/instance :: live";
    }

    @PostMapping("/instances/{name}/connect")
    public String connect(@PathVariable String name, @RequestParam(required = false) String number,
                          @RequestParam(required = false) String historySync,
                          @RequestParam(required = false) String next, RedirectAttributes flash) {
        if (historySync != null) {
            // Vale a partir deste pareamento: guarda antes de criar a conexão
            instances.setHistorySync(name, dev.msgforge.instance.HistorySync.parse(historySync));
        }
        // O QR anterior pode ter expirado (ex.: reconexão com sessão salva, que não gera QR novo)
        qrCodes.forget(name);
        var result = instances.connect(name, blankToNull(number));
        // Se o gateway devolveu o código atual (conexão já pendente), ele não é republicado como evento
        qrCodes.remember(name, result.code(), result.pairingCode(), result.base64());
        // Da conexão guiada (gerar outro QR): volta para ela, e não para o painel
        if ("wizard".equals(next)) {
            return "redirect:" + NewInstanceController.connectPage(name);
        }
        flash.addFlashAttribute("success", "manager.flash.connecting");
        return redirectTo(name);
    }

    @PostMapping("/instances/{name}/webhook")
    public String webhook(@PathVariable String name, @RequestParam String url,
                          @RequestParam(defaultValue = "false") boolean enabled,
                          @RequestParam(defaultValue = "false") boolean byEvents,
                          @RequestParam(required = false) List<String> events,
                          RedirectAttributes flash) {
        webhooks.set(name, new WebhookConfig(enabled, url.trim(), null, byEvents, false,
                events == null ? List.of() : events));
        flash.addFlashAttribute("success", "manager.flash.webhookSaved");
        return redirectTo(name);
    }

    /** Comportamentos e histórico do pareamento. Valem na hora, também na conexão em curso. */
    @PostMapping("/instances/{name}/settings")
    public String settings(@PathVariable String name,
                           @RequestParam(defaultValue = "false") boolean rejectCall,
                           @RequestParam(required = false) String msgCall,
                           @RequestParam(defaultValue = "false") boolean groupsIgnore,
                           @RequestParam(defaultValue = "false") boolean alwaysOnline,
                           @RequestParam(defaultValue = "false") boolean readMessages,
                           @RequestParam(defaultValue = "false") boolean readStatus,
                           @RequestParam(required = false) String historySync,
                           RedirectAttributes flash) {
        var typed = new SettingsView(rejectCall, msgCall == null ? "" : msgCall, groupsIgnore, alwaysOnline,
                readMessages, readStatus, historySync == null ? null : HistorySync.parse(historySync).name());
        try {
            var settings = new InstanceSettings(rejectCall, InstanceService.msgCall(msgCall), groupsIgnore,
                    alwaysOnline, readMessages, readStatus);
            instances.updateSettings(name, settings, historySync == null ? null : HistorySync.parse(historySync));
        } catch (InvalidFieldException e) {
            return invalid(name, "settings", typed, e, flash);
        }
        flash.addFlashAttribute("settingsSaved", true);
        return redirectTo(name) + "#settings";
    }

    /**
     * Proxy da instância. A senha nunca volta para a tela: em branco, a guardada fica (se o usuário é o
     * mesmo); {@code removePassword} a apaga. Vale a partir da próxima conexão.
     */
    @PostMapping("/instances/{name}/proxy")
    public String proxy(@PathVariable String name,
                        @RequestParam(defaultValue = "false") boolean enabled,
                        @RequestParam(required = false) String protocol,
                        @RequestParam(required = false) String host,
                        @RequestParam(required = false) String port,
                        @RequestParam(required = false) String username,
                        @RequestParam(required = false) String password,
                        @RequestParam(defaultValue = "false") boolean removePassword,
                        RedirectAttributes flash) {
        var hadPassword = instances.get(name).getProxy().map(p -> p.hasPassword()).orElse(false);
        var typed = new ProxyView(enabled, orEmpty(protocol), orEmpty(host), orEmpty(port), orEmpty(username),
                hadPassword);
        try {
            var fields = new InstanceService.ProxyFields(host, port, protocol, username,
                    removePassword ? null : password);
            instances.updateProxy(name, enabled, fields, !removePassword);
        } catch (InvalidFieldException e) {
            return invalid(name, "proxy", typed, e, flash);
        }
        flash.addFlashAttribute("proxySaved", true);
        return redirectTo(name) + "#proxy";
    }

    /** Volta para a tela com o que foi digitado (sem senha) e o erro preso ao campo. */
    private static String invalid(String name, String form, Object typed, InvalidFieldException e,
                                  RedirectAttributes flash) {
        flash.addFlashAttribute(form, typed);
        flash.addFlashAttribute("fieldErrors", java.util.Map.of(form + "." + e.field(),
                "manager." + form + ".error." + e.field() + "." + e.reason()));
        return redirectTo(name) + "#" + form;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    @PostMapping("/instances/{name}/send")
    public String send(@PathVariable String name, @RequestParam String number, @RequestParam String text,
                       RedirectAttributes flash) {
        if (text.isBlank()) {
            flash.addFlashAttribute("error", "manager.flash.textRequired");
            return redirectTo(name);
        }
        var sent = messages.sendText(name, number, text, null, null);
        history.recordSent(sent.instance(), sent.message(), "conversation", java.util.Map.of("conversation", text));
        flash.addFlashAttribute("success", "manager.flash.sent");
        flash.addFlashAttribute("sentId", sent.message().id());
        return redirectTo(name);
    }

    // ------------------------------------------------------------ mensagens (histórico)

    @GetMapping("/instances/{name}/messages")
    public String messages(@PathVariable String name, Model model) {
        var instance = instances.get(name);
        model.addAttribute("instanceName", instance.getName());
        model.addAttribute("chats", chatRows(name));
        return "manager/messages";
    }

    @GetMapping("/instances/{name}/messages/chats")
    public String chatList(@PathVariable String name, Model model) {
        model.addAttribute("instanceName", name);
        model.addAttribute("chats", chatRows(name));
        return "manager/messages :: chats";
    }

    @GetMapping("/instances/{name}/messages/chat")
    public String conversation(@PathVariable String name, @RequestParam String jid,
                               @RequestParam(defaultValue = "1") int page, Model model) {
        model.addAttribute("instanceName", name);
        model.addAttribute("conversation", screens.conversation(name, jid, page));
        return "manager/messages :: conversation";
    }

    @PostMapping("/instances/{name}/logout")
    public String logout(@PathVariable String name, RedirectAttributes flash) {
        instances.logout(name);
        qrCodes.forget(name);
        flash.addFlashAttribute("success", "manager.flash.loggedOut");
        return redirectTo(name);
    }

    @PostMapping("/instances/{name}/delete")
    public String delete(@PathVariable String name, RedirectAttributes flash) {
        instances.delete(name);
        qrCodes.forget(name);
        flash.addFlashAttribute("success", "manager.flash.deleted");
        flash.addFlashAttribute("deletedName", name);
        return "redirect:/manager";
    }

    // ------------------------------------------------------------ apoio

    private List<InstanceRow> rows() {
        return screens.rows();
    }

    private Live live(String name) {
        return screens.live(name);
    }

    private List<ManagerViews.ChatRow> chatRows(String name) {
        return screens.chatRows(name);
    }

    static String redirectTo(String name) {
        return "redirect:/manager/instances/" + UriUtils.encodePathSegment(name, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
