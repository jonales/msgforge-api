package dev.msgforge.gateway.cobalt;

import com.github.auties00.cobalt.stanza.model.Stanza;
import com.github.auties00.cobalt.wire.core.jid.Jid;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Traduz LID → número de telefone nos JIDs que vão para o JSON do contrato.
 *
 * <p>O WhatsApp endereça os contatos por LID (identificador opaco, {@code <lid>@lid}). O Cobalt
 * guarda o mapeamento LID ↔ número no próprio Store ({@code contactStore().findPhoneByLid}); o
 * resolver consulta o Store e, como reforço, aprende os pares com os nós recebidos, antes de a
 * mensagem decodificada chegar: {@code sender_pn} (conversa), {@code participant_pn} (grupo),
 * {@code peer_recipient_pn} (enviada por outro aparelho da conta).
 *
 * <p>Sem tradução conhecida, o LID sai como {@code <lid>@lid}, nunca como um número falso.
 */
final class LidResolver implements Function<Jid, String> {

    /** Pares (atributo com o LID, atributo com o número) nos nós de mensagem. */
    private static final String[][] PAIRS = {
            {"from", "sender_pn"}, {"participant", "participant_pn"}, {"recipient", "peer_recipient_pn"}};

    /** usuário do LID → JID do número, sem aparelho. */
    private final Map<String, String> phoneByLid = new ConcurrentHashMap<>();
    /** Consulta ao Store do Cobalt (LID de usuário → número); vazio quando não conhece. */
    private final Function<Jid, Optional<Jid>> store;

    LidResolver() {
        this(lid -> Optional.empty());
    }

    LidResolver(Function<Jid, Optional<Jid>> store) {
        this.store = store;
    }

    /** Nó recebido: aprende os pares LID ↔ número que ele traz. */
    void observe(Stanza node) {
        if (!node.hasDescription("message")) {
            return;
        }
        for (var pair : PAIRS) {
            var lid = node.getAttributeAsString(pair[0]).map(LidResolver::jid).orElse(null);
            var phone = node.getAttributeAsString(pair[1]).map(LidResolver::jid).orElse(null);
            if (lid != null && phone != null) {
                learn(lid, phone);
            }
        }
    }

    /** Registra um par (por exemplo, o LID e o número da própria conta, do Store). */
    void learn(Jid lid, Jid phone) {
        if (lid.hasLidServer() && phone.hasUserServer()) {
            phoneByLid.put(lid.user(), phone.toUserJid().toString());
        }
    }

    @Override
    public String apply(Jid jid) {
        if (jid == null) {
            return null;
        }
        if (jid.hasLidServer()) {
            var phone = phoneByLid.get(jid.user());
            if (phone != null) {
                return phone;
            }
            return store.apply(jid.toUserJid())
                    .filter(Jid::hasUserServer)
                    .map(found -> found.toUserJid().toString())
                    .orElseGet(() -> jid.toUserJid().toString());
        }
        return jid.hasUserServer() ? jid.toUserJid().toString() : jid.toString();
    }

    private static Jid jid(String value) {
        try {
            return Jid.of(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
