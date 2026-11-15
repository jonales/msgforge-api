package dev.msgforge.web;

import dev.msgforge.access.AccessService;
import dev.msgforge.access.Permission;
import dev.msgforge.event.EventType;
import dev.msgforge.instance.InstanceDtos.CreateInstanceRequest;
import dev.msgforge.common.ApiExceptionHandler.InvalidFieldException;
import dev.msgforge.instance.HistorySync;
import dev.msgforge.instance.InstanceService;
import dev.msgforge.instance.InstanceSettings;
import dev.msgforge.message.MessageService;
import dev.msgforge.web.ManagerViews.InstanceDetail;
import dev.msgforge.web.ManagerViews.PanelAccess;
import dev.msgforge.web.ManagerViews.ProxyView;
import dev.msgforge.web.ManagerViews.SettingsView;
import dev.msgforge.web.ManagerViews.WebhookView;
import dev.msgforge.webhook.WebhookDtos.WebhookConfig;
import dev.msgforge.webhook.WebhookService;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
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
    private final ManagerAccess guard;
    private final AccessService access;
    private final ManagerAuthor author;

    public ManagerController(InstanceService instances, WebhookService webhooks, MessageService messages,
                             QrCodeCache qrCodes, ManagerScreens screens,
                             dev.msgforge.history.HistoryRecorder history, ManagerAccess guard,
                             AccessService access, ManagerAuthor author) {
        this.guard = guard;
        this.author = author;
        this.access = access;
        this.instances = instances;
        this.webhooks = webhooks;
        this.messages = messages;
        this.qrCodes = qrCodes;
        this.screens = screens;
        this.history = history;
    }

    // ------------------------------------------------------------ lista

    /** A busca e o filtro da lista só aparecem acima disto: com poucas instâncias, atrapalham. */
    static final int SEARCH_THRESHOLD = 8;

    /** Canais e integrações sem backend: aparecem como "Em breve" (PRODUCT.md), sem link nem botão. */
    static final List<String> SOON_CHANNELS = List.of("cloudApi");
    static final List<String> SOON_INTEGRATIONS = List.of("typebot", "chatwoot", "openai", "dify", "flowise", "n8n");

    /** Só as instâncias em que a pessoa tem "ver status"; o resumo conta só essas. */
    @GetMapping({"", "/"})
    public String list(Model model) {
        var check = guard.current();
        var rows = screens.rows(check);
        model.addAttribute("instances", rows);
        model.addAttribute("canCreate", check.canSomewhere(Permission.INSTANCE_CREATE));
        model.addAttribute("showSearch", rows.size() > SEARCH_THRESHOLD);
        return "manager/instances";
    }

    /** Trecho da tabela: o polling do htmx busca este; o SSE (ADR 0013) envia o mesmo trecho. */
    @GetMapping("/instances/table")
    public String table(Model model) {
        var check = guard.current();
        model.addAttribute("instances", screens.rows(check));
        model.addAttribute("canCreate", check.canSomewhere(Permission.INSTANCE_CREATE));
        return "manager/instances :: table";
    }

    /**
     * Criação direta, sem os passos (a tela usa a guiada, {@link NewInstanceController}). Vai para a
     * organização {@code org}, se a pessoa pode criar nela, ou para a primeira em que pode.
     */
    @PostMapping("/instances")
    public String create(@RequestParam String instanceName, @RequestParam(required = false) String number,
                         @RequestParam(required = false) String historySync,
                         @RequestParam(required = false) java.util.UUID org, RedirectAttributes flash) {
        var organization = NewInstanceController.organizationFor(access, ManagerTopbar.current(), org)
                .orElseThrow(() -> new AccessDeniedException("Sem a permissão de criar instância"));
        var name = instanceName.trim();
        var created = instances.create(new CreateInstanceRequest(name, null, false, blankToNull(number), null, null),
                organization.getId());
        instances.setHistorySync(name, dev.msgforge.instance.HistorySync.parse(historySync));
        flash.addFlashAttribute("success", "manager.flash.created");
        // O token só aparece uma vez, logo após a criação
        flash.addFlashAttribute("createdToken", created.hash());
        return redirectTo(name);
    }

    // ------------------------------------------------------------ detalhe

    /**
     * O painel da instância é um conjunto de telas, uma por item do menu (URL própria, voltar e recarregar
     * funcionam): visão geral, conexão, mensagens, envio de teste e configurações. Cada tela pede a
     * permissão dela; o menu mostra só o que a pessoa pode abrir ({@link PanelAccess}).
     */
    private PanelAccess panel(Model model, dev.msgforge.access.AccessCheck check,
                              dev.msgforge.instance.Instance instance) {
        var can = PanelAccess.of(ManagerAccess.on(check, instance));
        model.addAttribute("can", can);
        model.addAttribute("instance", InstanceDetail.of(instance, instances.stateOf(instance)));
        return can;
    }

    /** Visão geral: estado, número, perfil, motor, aviso do webhook e atalhos. Pede "ver status". */
    @GetMapping("/instances/{name}")
    public String detail(@PathVariable String name, Model model) {
        summaryModel(name, model);
        return "manager/instance";
    }

    /** O resumo da visão geral, buscado de novo a cada evento "live" do SSE (e a cada 30 s). */
    @GetMapping("/instances/{name}/summary")
    public String summary(@PathVariable String name, Model model) {
        summaryModel(name, model);
        return "manager/instance :: summary";
    }

    private void summaryModel(String name, Model model) {
        var check = guard.current();
        panel(model, check, guard.require(check, name, Permission.INSTANCE_VIEW));
        // O motor real (Cobalt ou o simulado); o "integration" do contrato fica só na API
        model.addAttribute("engine", instances.engine());
        model.addAttribute("deliveryFailing", screens.deliveryFailing(name));
    }

    /**
     * Conexão: o estado ao vivo (com o QR para quem pode conectar), o formulário de conectar (só fora do
     * ar) e, numa seção de perigo, desconectar e excluir. Pede "ver status".
     */
    @GetMapping("/instances/{name}/connection")
    public String connection(@PathVariable String name, Model model) {
        var check = guard.current();
        var can = panel(model, check, guard.require(check, name, Permission.INSTANCE_VIEW));
        model.addAttribute("live", screens.live(name, can.connect()));
        model.addAttribute("historyOptions", HISTORY_OPTIONS);
        return "manager/instance-connection";
    }

    /** Envio de teste: um número e um texto. Pede "enviar mensagens". */
    @GetMapping("/instances/{name}/send")
    public String sendPage(@PathVariable String name, Model model) {
        var check = guard.current();
        panel(model, check, guard.require(check, name, Permission.CHAT_SEND));
        return "manager/instance-send";
    }

    /** Integrações e canais da instância: por enquanto só a lista "Em breve" (quem vê a instância). */
    @GetMapping("/instances/{name}/integrations")
    public String integrationsPage(@PathVariable String name, Model model) {
        var check = guard.current();
        panel(model, check, guard.require(check, name, Permission.INSTANCE_VIEW));
        model.addAttribute("soonChannels", SOON_CHANNELS);
        model.addAttribute("soonIntegrations", SOON_INTEGRATIONS);
        return "manager/instance-integrations";
    }

    /** Estado + QR, atualizado pelo htmx enquanto a instância não está conectada. */
    @GetMapping("/instances/{name}/live")
    public String liveFragment(@PathVariable String name, Model model) {
        var check = guard.current();
        var instance = guard.require(check, name, Permission.INSTANCE_VIEW);
        model.addAttribute("live", screens.live(name, check.can(Permission.INSTANCE_CONNECT, instance)));
        return LIVE_FRAGMENT;
    }

    /** O trecho ao vivo da conexão; o SSE ({@link ManagerStreams}) e a conexão guiada usam o mesmo. */
    static final String LIVE_TEMPLATE = "manager/instance-connection";
    static final String LIVE_FRAGMENT = LIVE_TEMPLATE + " :: live";

    @PostMapping("/instances/{name}/connect")
    public String connect(@PathVariable String name, @RequestParam(required = false) String number,
                          @RequestParam(required = false) String historySync,
                          @RequestParam(required = false) String next, RedirectAttributes flash) {
        guard.require(name, Permission.INSTANCE_CONNECT);
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
        return redirectTo(name, "connection");
    }

    @PostMapping("/instances/{name}/webhook")
    public String webhook(@PathVariable String name, @RequestParam String url,
                          @RequestParam(defaultValue = "false") boolean enabled,
                          @RequestParam(defaultValue = "false") boolean byEvents,
                          @RequestParam(required = false) List<String> events,
                          RedirectAttributes flash) {
        guard.require(name, Permission.EVENTS_CONFIGURE);
        webhooks.set(name, new WebhookConfig(enabled, url.trim(), null, byEvents, false,
                events == null ? List.of() : events));
        flash.addFlashAttribute("saved", "webhook");
        return "redirect:" + settingsPage(name, "webhook");
    }

    // ------------------------------------------------------------ configurações (uma tela por grupo)

    /** Os grupos de configuração, na ordem das abas; {@code webhook} pede outra permissão. */
    static final List<String> SETTINGS_GROUPS = List.of("calls", "reading", "presence", "attendance", "history",
            "proxy", "webhook");

    static Permission permissionOf(String group) {
        if (!SETTINGS_GROUPS.contains(group)) {
            throw new dev.msgforge.common.ApiExceptionHandler.NotFoundException("Unknown settings group: " + group);
        }
        return "webhook".equals(group) ? Permission.EVENTS_CONFIGURE : Permission.ACCOUNT_CONFIGURE;
    }

    /** "Configurações" leva ao primeiro grupo que a pessoa pode mudar. */
    @GetMapping("/instances/{name}/settings")
    public String settingsHome(@PathVariable String name) {
        var check = guard.current();
        var can = PanelAccess.of(ManagerAccess.on(check, guard.visible(check, name)));
        if (!can.settings()) {
            throw new AccessDeniedException("Sem permissão de configurar a instância");
        }
        return "redirect:" + settingsPage(name, can.account() ? "calls" : "webhook");
    }

    /** Um grupo de configurações: abas no topo, um formulário e um "salvar" por grupo. */
    @GetMapping("/instances/{name}/settings/{group}")
    public String settingsGroup(@PathVariable String name, @PathVariable String group, Model model) {
        var check = guard.current();
        var instance = guard.require(check, name, permissionOf(group));
        model.addAttribute("can", PanelAccess.of(ManagerAccess.on(check, instance)));
        model.addAttribute("instance", InstanceDetail.of(instance, instances.stateOf(instance)));
        model.addAttribute("group", group);
        model.addAttribute("groups", SETTINGS_GROUPS);
        if (!model.containsAttribute("settings")) {
            model.addAttribute("settings", SettingsView.of(instance));
        }
        if (!model.containsAttribute("proxy")) {
            model.addAttribute("proxy", ProxyView.of(instance));
        }
        model.addAttribute("savedProxy", ProxyView.of(instance));
        model.addAttribute("savedSettings", SettingsView.of(instance));
        model.addAttribute("webhook", webhooks.find(name).map(WebhookView::of).orElse(WebhookView.EMPTY));
        model.addAttribute("eventNames", EVENT_NAMES);
        model.addAttribute("historyOptions", HISTORY_OPTIONS);
        model.addAttribute("proxyProtocols", ProxyView.PROTOCOLS);
        model.addAttribute("secretsEnabled", instances.secretsEnabled());
        return "manager/settings";
    }

    /**
     * Salva um grupo e só ele: o que não está no formulário fica como estava (um interruptor ausente
     * do POST de outro grupo não pode desligar nada). Vale na hora, também na conexão em curso.
     */
    @PostMapping("/instances/{name}/settings/{group}")
    public String saveSettings(@PathVariable String name, @PathVariable String group,
                               @RequestParam(defaultValue = "false") boolean rejectCall,
                               @RequestParam(required = false) String msgCall,
                               @RequestParam(defaultValue = "false") boolean groupsIgnore,
                               @RequestParam(defaultValue = "false") boolean alwaysOnline,
                               @RequestParam(defaultValue = "false") boolean readMessages,
                               @RequestParam(defaultValue = "false") boolean readStatus,
                               @RequestParam(required = false) String historySync,
                               @RequestParam(defaultValue = "false") boolean signMessages,
                               RedirectAttributes flash) {
        if (!List.of("calls", "reading", "presence", "attendance", "history").contains(group)) {
            throw new dev.msgforge.common.ApiExceptionHandler.NotFoundException("Unknown settings group: " + group);
        }
        var instance = guard.require(name, Permission.ACCOUNT_CONFIGURE);
        if ("attendance".equals(group)) {   // fora do InstanceSettings: não muda nada na conexão
            instances.setSignMessages(name, signMessages);
            flash.addFlashAttribute("saved", group);
            return "redirect:" + settingsPage(name, group);
        }
        var s = instance.getSettings();
        var stored = SettingsView.of(instance);
        try {
            var settings = switch (group) {
                case "calls" -> new InstanceSettings(rejectCall, InstanceService.msgCall(msgCall), s.groupsIgnore(),
                        s.alwaysOnline(), s.readMessages(), s.readStatus());
                case "reading" -> new InstanceSettings(s.rejectCall(), s.msgCall(), groupsIgnore, s.alwaysOnline(),
                        readMessages, readStatus);
                case "presence" -> new InstanceSettings(s.rejectCall(), s.msgCall(), s.groupsIgnore(), alwaysOnline,
                        s.readMessages(), s.readStatus());
                default -> s;
            };
            var history = "history".equals(group) && historySync != null ? HistorySync.parse(historySync) : null;
            instances.updateSettings(name, settings, history);
        } catch (InvalidFieldException e) {
            // Volta com o que foi digitado neste grupo; os outros, como estão guardados
            var typed = new SettingsView(rejectCall, msgCall == null ? "" : msgCall, stored.groupsIgnore(),
                    stored.alwaysOnline(), stored.readMessages(), stored.readStatus(), stored.historySync(),
                    stored.signMessages());
            flash.addFlashAttribute("settings", typed);
            flash.addFlashAttribute("fieldErrors", java.util.Map.of("settings." + e.field(),
                    "manager.settings.error." + e.field() + "." + e.reason()));
            return "redirect:" + settingsPage(name, group);
        }
        flash.addFlashAttribute("saved", group);
        return "redirect:" + settingsPage(name, group);
    }

    static String settingsPage(String name, String group) {
        return "/manager/instances/" + UriUtils.encodePathSegment(name, java.nio.charset.StandardCharsets.UTF_8)
                + "/settings/" + group;
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
        var hadPassword = guard.require(name, Permission.ACCOUNT_CONFIGURE).getProxy().map(p -> p.hasPassword()).orElse(false);
        var typed = new ProxyView(enabled, orEmpty(protocol), orEmpty(host), orEmpty(port), orEmpty(username),
                hadPassword);
        try {
            var fields = new InstanceService.ProxyFields(host, port, protocol, username,
                    removePassword ? null : password);
            instances.updateProxy(name, enabled, fields, !removePassword);
        } catch (InvalidFieldException e) {
            flash.addFlashAttribute("proxy", typed);
            flash.addFlashAttribute("fieldErrors", java.util.Map.of("proxy." + e.field(),
                    "manager.proxy.error." + e.field() + "." + e.reason()));
            return "redirect:" + settingsPage(name, "proxy");
        }
        flash.addFlashAttribute("saved", "proxy");
        return "redirect:" + settingsPage(name, "proxy");
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    @PostMapping("/instances/{name}/send")
    public String send(@PathVariable String name, @RequestParam String number, @RequestParam String text,
                       RedirectAttributes flash) {
        guard.require(name, Permission.CHAT_SEND);
        if (text.isBlank()) {
            flash.addFlashAttribute("error", "manager.flash.textRequired");
            return redirectTo(name, "send");
        }
        var sent = messages.sendText(name, number, text, null, null);   // o Envio de teste nunca assina
        history.recordSent(sent.instance(), sent.message(), "conversation", java.util.Map.of("conversation", text),
                author.current().sentBy());
        flash.addFlashAttribute("success", "manager.flash.sent");
        flash.addFlashAttribute("sentId", sent.message().id());
        return redirectTo(name, "send");
    }

    // ------------------------------------------------------------ mensagens (histórico)

    /**
     * Conversas e, com {@code jid}, a conversa aberta. Abrir uma conversa da lista põe o {@code jid} na
     * URL (hx-push-url): recarregar e voltar funcionam, e no celular a lista e a conversa são telas
     * separadas.
     */
    @GetMapping("/instances/{name}/messages")
    public String messages(@PathVariable String name, @RequestParam(required = false) String jid, Model model) {
        var check = guard.current();
        var instance = guard.require(check, name, Permission.CHAT_VIEW);
        model.addAttribute("instanceName", instance.getName());
        model.addAttribute("can", PanelAccess.of(ManagerAccess.on(check, instance)));
        model.addAttribute("chats", chatRows(name));
        if (jid != null && !jid.isBlank()) {
            model.addAttribute("conversation", screens.conversation(name, jid.trim(), 1));
        }
        return "manager/messages";
    }

    @GetMapping("/instances/{name}/messages/chats")
    public String chatList(@PathVariable String name, Model model) {
        guard.require(name, Permission.CHAT_VIEW);
        model.addAttribute("instanceName", name);
        model.addAttribute("chats", chatRows(name));
        return "manager/messages :: chats";
    }

    @GetMapping("/instances/{name}/messages/chat")
    public String conversation(@PathVariable String name, @RequestParam String jid,
                               @RequestParam(defaultValue = "1") int page, Model model) {
        guard.require(name, Permission.CHAT_VIEW);
        model.addAttribute("instanceName", name);
        model.addAttribute("conversation", screens.conversation(name, jid, page));
        return "manager/messages :: conversation";
    }

    /**
     * Responder na conversa aberta: o mesmo envio do "Envio de teste" (pede "enviar mensagens"), para o
     * contato ou o grupo da conversa. Pelo htmx, devolve a conversa atualizada; um erro volta como 422
     * com o aviso, sem trocar a conversa (o texto digitado fica). Sem JS, volta para a conversa.
     */
    @PostMapping("/instances/{name}/messages/reply")
    public String reply(@PathVariable String name, @RequestParam String jid, @RequestParam String text,
                        @org.springframework.web.bind.annotation.RequestHeader(value = "HX-Request", required = false)
                        String htmx,
                        Model model, RedirectAttributes flash, jakarta.servlet.http.HttpServletResponse response) {
        var instance = guard.require(name, Permission.CHAT_SEND);
        var page = "redirect:/manager/instances/" + UriUtils.encodePathSegment(name, java.nio.charset.StandardCharsets.UTF_8)
                + "/messages?jid=" + UriUtils.encodeQueryParam(jid, java.nio.charset.StandardCharsets.UTF_8);
        String failure = null;
        if (text.isBlank()) {
            failure = "manager.messages.reply.empty";
        } else {
            try {
                var sender = author.current();
                var signed = ManagerAuthor.reply(instance, sender, text);
                var sent = messages.sendText(name, jid, signed, null, null);
                history.recordSent(sent.instance(), sent.message(), "conversation",
                        java.util.Map.of("conversation", signed), sender.sentBy());
            } catch (dev.msgforge.common.ApiExceptionHandler.BadRequestException
                     | dev.msgforge.gateway.WhatsAppGateway.GatewayException e) {
                failure = "manager.messages.reply.failed";
            }
        }
        if (htmx == null) {
            if (failure != null) {
                flash.addFlashAttribute("error", failure);
            }
            return page;
        }
        if (failure != null) {
            response.setStatus(422);
            model.addAttribute("replyError", failure);
            return "manager/messages :: replyError";
        }
        model.addAttribute("instanceName", name);
        model.addAttribute("conversation", screens.conversation(name, jid, 1));
        return "manager/messages :: conversation";
    }

    /** Desconectar é o avesso de conectar: a mesma permissão. */
    @PostMapping("/instances/{name}/logout")
    public String logout(@PathVariable String name, RedirectAttributes flash) {
        guard.require(name, Permission.INSTANCE_CONNECT);
        instances.logout(name);
        qrCodes.forget(name);
        flash.addFlashAttribute("success", "manager.flash.loggedOut");
        return redirectTo(name, "connection");
    }

    @PostMapping("/instances/{name}/delete")
    public String delete(@PathVariable String name, RedirectAttributes flash) {
        guard.require(name, Permission.INSTANCE_DELETE);
        instances.delete(name);
        qrCodes.forget(name);
        flash.addFlashAttribute("success", "manager.flash.deleted");
        flash.addFlashAttribute("deletedName", name);
        return "redirect:/manager";
    }

    // ------------------------------------------------------------ apoio

    private ManagerViews.ChatList chatRows(String name) {
        return screens.chatRows(name);
    }

    static String redirectTo(String name) {
        return "redirect:/manager/instances/" + UriUtils.encodePathSegment(name, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Uma tela do painel ({@code connection}, {@code send}...). */
    static String redirectTo(String name, String page) {
        return redirectTo(name) + "/" + page;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
