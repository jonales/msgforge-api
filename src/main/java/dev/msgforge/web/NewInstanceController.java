package dev.msgforge.web;

import dev.msgforge.common.ApiExceptionHandler.NotFoundException;
import dev.msgforge.gateway.WhatsAppGateway.GatewayException;
import dev.msgforge.instance.HistorySync;
import dev.msgforge.instance.InstanceDtos.CreateInstanceRequest;
import dev.msgforge.instance.InstanceService;
import dev.msgforge.web.ManagerViews.InstanceDetail;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Nova instância guiada: nome, forma de conexão, histórico e, por fim, a leitura do QR (ou do código).
 *
 * <p>Cada passo é uma página ({@code GET}) com o formulário dele ({@code POST} + redirect), então
 * avançar, voltar (pelo link ou pelo botão do navegador) e recarregar funcionam sem JavaScript. O que
 * já foi respondido fica num rascunho na sessão, e não na URL (o número do pareamento não vai para log
 * de acesso). A instância só é criada no passo do histórico, que já conecta; quem abandona a leitura
 * do QR encontra a instância na lista como "aguardando conexão", e o painel dela leva de volta a este
 * último passo.
 */
@Controller
@RequestMapping("/manager/instances")
public class NewInstanceController {

    static final String DRAFT = "manager.newInstance";
    static final String LAST_CREATED = "manager.newInstance.created";
    static final int TOTAL_STEPS = 4;

    /** O mesmo padrão do {@code /instance/create}. */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");
    /** Caminhos do Manager que um nome de instância esconderia ({@code /manager/instances/{nome}}). */
    private static final Set<String> RESERVED = Set.of("new", "table");
    /** Pareamento: DDI + DDD + número, só dígitos. */
    private static final Pattern PHONE = Pattern.compile("^[0-9]{10,15}$");

    static final String QR = "qr";
    static final String PAIRING = "pairing";

    /** O que o usuário já respondeu nos passos anteriores. Público: o template chama os métodos. */
    public record Draft(String name, String method, String number) implements Serializable {

        public boolean pairing() {
            return PAIRING.equals(method);
        }
    }

    private final InstanceService instances;
    private final QrCodeCache qrCodes;
    private final ManagerScreens screens;

    public NewInstanceController(InstanceService instances, QrCodeCache qrCodes, ManagerScreens screens) {
        this.instances = instances;
        this.qrCodes = qrCodes;
        this.screens = screens;
    }

    // ------------------------------------------------------------ passo 1: nome

    @GetMapping("/new")
    public String name(HttpSession session, Model model) {
        var draft = draft(session);
        if (!model.containsAttribute("typedName")) {
            model.addAttribute("typedName", draft == null ? "" : draft.name());
        }
        return step(model, 1);
    }

    @PostMapping("/new")
    public String saveName(@RequestParam(defaultValue = "") String instanceName, HttpSession session,
                           RedirectAttributes flash) {
        var name = instanceName.trim();
        var error = nameError(name);
        if (error != null) {
            flash.addFlashAttribute("typedName", name);
            flash.addFlashAttribute("fieldError", error);
            return "redirect:/manager/instances/new";
        }
        var draft = draft(session);
        session.setAttribute(DRAFT, draft == null ? new Draft(name, QR, null) : new Draft(name, draft.method(), draft.number()));
        return "redirect:/manager/instances/new/connection";
    }

    private String nameError(String name) {
        if (name.isEmpty()) {
            return "manager.wizard.name.error.required";
        }
        if (!NAME.matcher(name).matches()) {
            return "manager.wizard.name.error.pattern";
        }
        if (RESERVED.contains(name.toLowerCase(java.util.Locale.ROOT))) {
            return "manager.wizard.name.error.reserved";
        }
        return exists(name) ? "manager.wizard.name.error.taken" : null;
    }

    private boolean exists(String name) {
        try {
            instances.get(name);
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }

    // ------------------------------------------------------------ passo 2: como conectar

    @GetMapping("/new/connection")
    public String connection(HttpSession session, Model model) {
        var draft = draft(session);
        if (draft == null) {
            return withoutDraft(session, model, 2);
        }
        model.addAttribute("draft", draft);
        if (!model.containsAttribute("typedMethod")) {
            model.addAttribute("typedMethod", draft.method());
            model.addAttribute("typedNumber", draft.number() == null ? "" : draft.number());
        }
        return step(model, 2);
    }

    @PostMapping("/new/connection")
    public String saveConnection(@RequestParam(defaultValue = QR) String method,
                                 @RequestParam(defaultValue = "") String number,
                                 HttpSession session, RedirectAttributes flash) {
        var draft = draft(session);
        if (draft == null) {
            return "redirect:/manager/instances/new";
        }
        var chosen = PAIRING.equals(method) ? PAIRING : QR;
        var digits = number.replaceAll("[\\s()+.-]", "");
        if (PAIRING.equals(chosen) && !PHONE.matcher(digits).matches()) {
            flash.addFlashAttribute("typedMethod", chosen);
            flash.addFlashAttribute("typedNumber", number.trim());
            flash.addFlashAttribute("fieldError", digits.isEmpty()
                    ? "manager.wizard.number.error.required" : "manager.wizard.number.error.invalid");
            return "redirect:/manager/instances/new/connection";
        }
        session.setAttribute(DRAFT, new Draft(draft.name(), chosen, PAIRING.equals(chosen) ? digits : null));
        return "redirect:/manager/instances/new/history";
    }

    // ------------------------------------------------------------ passo 3: histórico (cria e conecta)

    @GetMapping("/new/history")
    public String history(HttpSession session, Model model) {
        var draft = draft(session);
        if (draft == null) {
            return withoutDraft(session, model, 3);
        }
        model.addAttribute("draft", draft);
        model.addAttribute("historyOptions", ManagerController.HISTORY_OPTIONS);
        return step(model, 3);
    }

    @PostMapping("/new/history")
    public String create(@RequestParam(required = false) String historySync, HttpSession session,
                         RedirectAttributes flash) {
        var draft = draft(session);
        if (draft == null) {
            return "redirect:/manager/instances/new";
        }
        var name = draft.name();
        // O nome pode ter sido ocupado depois do passo 1 (outra aba, a API): volta para ele com o erro
        var error = nameError(name);
        if (error != null) {
            flash.addFlashAttribute("typedName", name);
            flash.addFlashAttribute("fieldError", error);
            return "redirect:/manager/instances/new";
        }
        var created = instances.create(new CreateInstanceRequest(name, null, false, draft.number(), null, null));
        // Antes de conectar: o histórico vale para este pareamento
        instances.setHistorySync(name, HistorySync.parse(historySync));
        session.removeAttribute(DRAFT);
        session.setAttribute(LAST_CREATED, name);
        // O token só aparece uma vez, logo após a criação
        flash.addFlashAttribute("createdToken", created.hash());
        try {
            qrCodes.forget(name);
            var result = instances.connect(name, draft.number());
            qrCodes.remember(name, result.code(), result.pairingCode(), result.base64());
        } catch (GatewayException e) {
            // A instância já existe: a tela de conexão mostra o erro e oferece tentar de novo
            flash.addFlashAttribute("errorText", e.getMessage());
        }
        return "redirect:" + connectPage(name);
    }

    // ------------------------------------------------------------ passo 4: ler o QR ou digitar o código

    @GetMapping("/{name}/connect")
    public String connect(@PathVariable String name, Model model) {
        var instance = instances.get(name);
        var detail = InstanceDetail.of(instance, instances.stateOf(instance));
        model.addAttribute("instance", detail);
        model.addAttribute("live", screens.live(name));
        // Para gerar outro código: o número guardado na criação indica o pareamento por código
        model.addAttribute("pairingNumber", instance.getNumber());
        model.addAttribute("historySync", instance.getHistorySync().name());
        return step(model, 4);
    }

    // ------------------------------------------------------------ apoio

    /**
     * Passos 2 e 3 sem rascunho. Logo depois de criar (o botão Voltar do navegador), a página diz que a
     * instância já existe e leva à conexão dela, em vez de redirecionar (o que prenderia o Voltar).
     */
    private String withoutDraft(HttpSession session, Model model, int step) {
        if (session.getAttribute(LAST_CREATED) instanceof String name && exists(name)) {
            model.addAttribute("createdName", name);
            return step(model, step);
        }
        return "redirect:/manager/instances/new";
    }

    private static String step(Model model, int step) {
        model.addAttribute("step", step);
        model.addAttribute("totalSteps", TOTAL_STEPS);
        return step == 4 ? "manager/connect" : "manager/new-instance";
    }

    private static Draft draft(HttpSession session) {
        return session.getAttribute(DRAFT) instanceof Draft draft ? draft : null;
    }

    static String connectPage(String name) {
        return ManagerController.redirectTo(name).substring("redirect:".length()) + "/connect";
    }
}
