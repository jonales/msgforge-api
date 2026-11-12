package dev.msgforge.gateway.cobalt;

import it.auties.whatsapp.model.jid.Jid;
import it.auties.whatsapp.model.jid.JidServer;
import it.auties.whatsapp.model.node.Node;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Traduz LID → número de telefone nos JIDs que vão para o JSON do contrato.
 *
 * <p>O WhatsApp endereça os contatos por LID (identificador opaco), e o Cobalt 0.0.10 não traduz:
 * um JID de LID chega como {@code 249533976023044_1:16@s.whatsapp.net} (agente 1 = domínio LID).
 * O número vem nos próprios nós: {@code sender_pn} (conversa), {@code participant_pn} (grupo),
 * {@code peer_recipient_pn} (enviada por outro aparelho da conta). O resolver aprende os pares com
 * cada nó recebido, antes de o Cobalt entregar a mensagem decodificada.
 *
 * <p>Sem tradução conhecida, o LID sai como {@code <lid>@lid}, nunca como um número falso
 * ({@code toSimpleJid()} descarta o agente e faria o LID parecer um telefone).
 */
final class LidResolver implements Function<Jid, String> {

    /** Pares (atributo com o LID, atributo com o número) nos nós de mensagem. */
    private static final String[][] PAIRS = {
            {"from", "sender_pn"}, {"participant", "participant_pn"}, {"recipient", "peer_recipient_pn"}};

    /** usuário do LID → JID do número, sem aparelho. */
    private final Map<String, String> phoneByLid = new ConcurrentHashMap<>();

    /** Nó recebido: aprende os pares LID ↔ número que ele traz. */
    void observe(Node node) {
        if (!node.hasDescription("message")) {
            return;
        }
        var attributes = node.attributes().toMap();
        for (var pair : PAIRS) {
            var lid = jid(attributes.get(pair[0]));
            var phone = jid(attributes.get(pair[1]));
            if (lid != null && phone != null) {
                learn(lid, phone);
            }
        }
    }

    /** Registra um par (por exemplo, o LID e o número da própria conta, do Store). */
    void learn(Jid lid, Jid phone) {
        if (isLid(lid) && !isLid(phone) && phone.hasServer(JidServer.whatsapp())) {
            phoneByLid.put(lid.user(), phone.toSimpleJid().toString());
        }
    }

    @Override
    public String apply(Jid jid) {
        if (jid == null) {
            return null;
        }
        if (isLid(jid)) {
            var phone = phoneByLid.get(jid.user());
            return phone != null ? phone : jid.user() + "@" + JidServer.lid();
        }
        return jid.hasServer(JidServer.whatsapp()) ? jid.toSimpleJid().toString() : jid.toString();
    }

    static boolean isLid(Jid jid) {
        return jid.agent() == 1 || jid.hasServer(JidServer.lid());
    }

    private static Jid jid(Object value) {
        return switch (value) {
            case null -> null;
            case Jid jid -> jid;
            default -> {
                try {
                    yield Jid.of(value.toString());
                } catch (RuntimeException e) {
                    yield null;
                }
            }
        };
    }
}
