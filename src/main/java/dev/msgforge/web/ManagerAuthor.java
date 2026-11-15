package dev.msgforge.web;

import dev.msgforge.access.AccessAdminService;
import dev.msgforge.history.SentBy;
import dev.msgforge.instance.Instance;
import org.springframework.stereotype.Component;

/**
 * Quem está enviando pelo Manager e como a resposta sai: com a assinatura ligada na instância e um
 * nome de exibição, o nome vai em negrito na primeira linha e o texto embaixo. A chave global nunca
 * assina. O nome é lido do banco a cada envio (renomear vale na hora).
 */
@Component
public class ManagerAuthor {

    private final AccessAdminService admin;

    public ManagerAuthor(AccessAdminService admin) {
        this.admin = admin;
    }

    /** Quem envia, com o nome de agora (nome de exibição, senão o e-mail). */
    public Sender current() {
        var who = ManagerTopbar.current();
        if (who == null || who.globalKey()) {
            return new Sender(SentBy.MANAGER_KEY, null);
        }
        var displayName = admin.displayNameOf(who);
        return new Sender(SentBy.user(who.userId(), displayName != null ? displayName : who.email()), displayName);
    }

    /** O texto de uma resposta na conversa: assinado se a instância assina e a pessoa tem nome. */
    public static String reply(Instance instance, Sender sender, String text) {
        return instance.isSignMessages() && sender.displayName() != null ? sign(sender.displayName(), text) : text;
    }

    /** {@code *Nome*} na primeira linha (negrito no WhatsApp) e o texto embaixo. */
    static String sign(String displayName, String text) {
        return "*" + displayName + "*\n" + text;
    }

    /** @param displayName null sem nome de exibição (não assina) */
    public record Sender(SentBy sentBy, String displayName) {}
}
