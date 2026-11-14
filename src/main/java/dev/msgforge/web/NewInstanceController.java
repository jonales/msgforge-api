package dev.msgforge.web;

import dev.msgforge.common.ApiExceptionHandler.NotFoundException;
import dev.msgforge.gateway.WhatsAppGateway.GatewayException;
import dev.msgforge.instance.HistorySync;
import dev.msgforge.instance.InstanceDtos.CreateInstanceRequest;
import dev.msgforge.instance.InstanceService;
import dev.msgforge.web.ManagerViews.InstanceDetail;
import jakarta.servlet.http.HttpSession;
import dev.msgforge.access.AccessService;
import dev.msgforge.access.ManagerPrincipal;
import dev.msgforge.access.Organization;
import dev.msgforge.access.Permission;
import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
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
    public record Draft(String name, String method, String number, UUID organizationId) implements Serializable {

        public boolean pairing() {
            return PAIRING.equals(method);
        }
    }

    private final InstanceService instances;
    private final QrCodeCache qrCodes;
    private final ManagerScreens screens;
    private final AccessService access;
    private final ManagerAccess guard;

    public NewInstanceController(InstanceService instances, QrCodeCache qrCodes, ManagerScreens screens,
                                 AccessService access, ManagerAccess guard) {
        this.access = access;
        this.guard = guard;
        this.instances = instances;
        this.qrCodes = qrCodes;
        this.screens = screens;
    }

    // ------------------------------------------------------------ passo 1: nome

    /**
     * Com "criar instância" em mais de uma organização, o passo 1 também pergunta em qual (a lista só
     * aparece nesse caso); com uma só, a instância vai para ela.
     */
    @GetMapping("/new")
    public String name(HttpSession session, Model model) {
        var organizations = creatable();
        var draft = draft(session);
        if (!model.containsAttribute("typedName")) {
            model.addAttribute("typedName", draft == null ? "" : draft.name());
        }
        if (organizations.size() > 1) {
            model.addAttribute("organizations", organizations.stream().map(AccessViews.OrgOption::of).toList());
            model.addAttribute("typedOrg", draft == null ? organizations.getFirst().getId() : draft.organizationId());
        }
        return step(model, 1);
    }

    @PostMapping("/new")
    public String saveName(@RequestParam(defaultValue = "") String instanceName,
                           @RequestParam(required = false) UUID org, HttpSession session,
                           RedirectAttributes flash) {
        var organization = organizationFor(access, ManagerTopbar.current(), org)
                .orElseThrow(NewInstanceController::cannotCreate);
        var name = instanceName.trim();
        var error = nameError(name);
        if (error != null) {
            flash.addFlashAttribute("typedName", name);
            flash.addFlashAttribute("fieldError", error);
            return "redirect:/manager/instances/new";
        }
        var draft = draft(session);
        var orgId = organization.getId();
        session.setAttribute(DRAFT, draft == null ? new Draft(name, QR, null, orgId)
                : new Draft(name, draft.method(), draft.number(), orgId));
        return "redirect:/manager/instances/new/connection";
    }

    /** As organizações em que a pessoa pode criar; nenhuma, 403 em todos os passos. */
    private List<Organization> creatable() {
        var organizations = access.organizationsWhere(ManagerTopbar.current(), Permission.INSTANCE_CREATE);
        if (organizations.isEmpty()) {
            throw cannotCreate();
        }
        return organizations;
    }

    /**
     * A organização pedida, se a pessoa pode criar nela; sem pedido (ou com uma em que não pode),
     * a primeira em que pode, por nome. Vazio: não pode criar em nenhuma.
     */
    static Optional<Organization> organizationFor(AccessService access, ManagerPrincipal who, UUID requested) {
        if (who == null) {
            return Optional.empty();
        }
        var allowed = access.organizationsWhere(who, Permission.INSTANCE_CREATE);
        return allowed.stream().filter(o -> o.getId().equals(requested)).findFirst()
                .or(() -> requested == null ? allowed.stream().findFirst() : Optional.empty());
    }

    private static AccessDeniedException cannotCreate() {
        return new AccessDeniedException("Sem a permissão de criar instância");
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
        creatable();
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
        creatable();
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
        session.setAttribute(DRAFT, new Draft(draft.name(), chosen, PAIRING.equals(chosen) ? digits : null,
                draft.organizationId()));
        return "redirect:/manager/instances/new/history";
    }

    // ------------------------------------------------------------ passo 3: histórico (cria e conecta)

    @GetMapping("/new/history")
    public String history(HttpSession session, Model model) {
        creatable();
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
            creatable();
            return "redirect:/manager/instances/new";
        }
        // Confere de novo na criação: o papel pode ter mudado desde o passo 1
        var organization = organizationFor(access, ManagerTopbar.current(), draft.organizationId())
                .orElseThrow(NewInstanceController::cannotCreate);
        var name = draft.name();
        // O nome pode ter sido ocupado depois do passo 1 (outra aba, a API): volta para ele com o erro
        var error = nameError(name);
        if (error != null) {
            flash.addFlashAttribute("typedName", name);
            flash.addFlashAttribute("fieldError", error);
            return "redirect:/manager/instances/new";
        }
        var created = instances.create(new CreateInstanceRequest(name, null, false, draft.number(), null, null),
                organization.getId());
        // Antes de conectar: o histórico vale para este pareamento
        instances.setHistorySync(name, HistorySync.parse(historySync));
        session.removeAttribute(DRAFT);
        session.setAttribute(LAST_CREATED, name);
        // O token só aparece uma vez, logo após a criação
        flash.addFlashAttribute("createdToken", created.hash());
        // Um papel com "criar" e sem "conectar": a instância fica "aguardando conexão" para outra pessoa
        var check = guard.current();
        var instance = instances.get(name);
        if (!check.can(Permission.INSTANCE_CONNECT, instance)) {
            return check.can(Permission.INSTANCE_VIEW, instance) ? ManagerController.redirectTo(name) : "redirect:/manager";
        }
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
        var instance = guard.require(name, Permission.INSTANCE_CONNECT);
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
