package dev.msgforge.web;

import dev.msgforge.event.EventType;
import dev.msgforge.instance.InstanceDtos.CreateInstanceRequest;
import dev.msgforge.instance.InstanceService;
import dev.msgforge.message.MessageService;
import dev.msgforge.web.ManagerViews.InstanceDetail;
import dev.msgforge.web.ManagerViews.InstanceRow;
import dev.msgforge.web.ManagerViews.Live;
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

    static final int CONVERSATION_PAGE_SIZE = 30;

    private final InstanceService instances;
    private final WebhookService webhooks;
    private final MessageService messages;
    private final QrCodeCache qrCodes;
    private final dev.msgforge.chat.ChatService chats;
    private final dev.msgforge.history.HistoryRecorder history;

    public ManagerController(InstanceService instances, WebhookService webhooks, MessageService messages,
                             QrCodeCache qrCodes, dev.msgforge.chat.ChatService chats,
                             dev.msgforge.history.HistoryRecorder history) {
        this.instances = instances;
        this.webhooks = webhooks;
        this.messages = messages;
        this.qrCodes = qrCodes;
        this.chats = chats;
        this.history = history;
    }

    @GetMapping("/login")
    public String login() {
        return "manager/login";
    }

    // ------------------------------------------------------------ lista

    @GetMapping({"", "/"})
    public String list(Model model) {
        model.addAttribute("instances", rows());
        return "manager/instances";
    }

    /** Trecho da tabela, atualizado pelo htmx a cada poucos segundos. */
    @GetMapping("/instances/table")
    public String table(Model model) {
        model.addAttribute("instances", rows());
        return "manager/instances :: table";
    }

    @PostMapping("/instances")
    public String create(@RequestParam String instanceName, @RequestParam(required = false) String number,
                         RedirectAttributes flash) {
        var name = instanceName.trim();
        var created = instances.create(new CreateInstanceRequest(name, null, false, blankToNull(number), null, null));
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
                          RedirectAttributes flash) {
        // O QR anterior pode ter expirado (ex.: reconexão com sessão salva, que não gera QR novo)
        qrCodes.forget(name);
        var result = instances.connect(name, blankToNull(number));
        // Se o gateway devolveu o código atual (conexão já pendente), ele não é republicado como evento
        qrCodes.remember(name, result.code(), result.pairingCode(), result.base64());
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
        var current = Math.max(page, 1);
        var found = chats.findMessages(name, new dev.msgforge.chat.ChatDtos.KeyFilter(jid, null, null),
                current, CONVERSATION_PAGE_SIZE);
        var rows = new java.util.ArrayList<>(found.records().stream()
                .map(m -> new ManagerViews.MessageRow(m.key().id(), m.key().fromMe(), m.pushName(),
                        ManagerViews.preview(m.messageType(), m.message()), ManagerViews.time(m.messageTimestamp()),
                        m.status()))
                .toList());
        java.util.Collections.reverse(rows);   // a página vem da mais nova para a mais antiga
        model.addAttribute("instanceName", name);
        model.addAttribute("conversation", new ManagerViews.Conversation(jid, rows, current, found.pages()));
        return "manager/messages :: conversation";
    }

    private List<ManagerViews.ChatRow> chatRows(String name) {
        return chats.findChats(name).stream()
                .map(c -> new ManagerViews.ChatRow(c.remoteJid(), c.pushName(),
                        ManagerViews.preview(c.lastMessage().messageType(), c.lastMessage().message()),
                        ManagerViews.time(c.lastMessage().messageTimestamp()), c.lastMessage().key().fromMe()))
                .toList();
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
        return instances.list().stream().map(i -> InstanceRow.of(i, instances.stateOf(i))).toList();
    }

    private Live live(String name) {
        var instance = instances.get(name);
        var state = instances.stateOf(instance).value();
        var qr = qrCodes.get(name).orElse(null);
        return qr == null || "open".equals(state)
                ? new Live(name, state, null, null, null)
                : new Live(name, state, qr.code(), qr.base64(), qr.pairingCode());
    }

    static String redirectTo(String name) {
        return "redirect:/manager/instances/" + UriUtils.encodePathSegment(name, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
