package dev.msgforge.web;

import dev.msgforge.access.AccessCheck;
import dev.msgforge.access.Permission;
import dev.msgforge.chat.ChatDtos.KeyFilter;
import dev.msgforge.history.MessageHistory;
import dev.msgforge.instance.Instance;
import java.util.UUID;
import dev.msgforge.chat.ChatService;
import dev.msgforge.event.outbox.DeliveryHealth;
import dev.msgforge.instance.InstanceService;
import dev.msgforge.web.ManagerViews.ChatRow;
import dev.msgforge.web.ManagerViews.Conversation;
import dev.msgforge.web.ManagerViews.InstanceRow;
import dev.msgforge.web.ManagerViews.Live;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Monta os modelos dos trechos que se atualizam sozinhos (lista, conexão, conversas). Usado pelas
 * telas ({@link ManagerController}) e pelo tempo real ({@link ManagerStreams}), para que o trecho
 * enviado pelo SSE seja o mesmo que o polling busca.
 */
@Component
public class ManagerScreens {

    static final int CONVERSATION_PAGE_SIZE = 30;

    private final InstanceService instances;
    private final QrCodeCache qrCodes;
    private final ChatService chats;
    private final DeliveryHealth deliveries;
    private final GroupNames groupNames;
    private final MessageHistory history;
    private final dev.msgforge.bot.BotService bots;

    public ManagerScreens(InstanceService instances, QrCodeCache qrCodes, ChatService chats,
                          DeliveryHealth deliveries, GroupNames groupNames, MessageHistory history,
                          dev.msgforge.bot.BotService bots) {
        this.groupNames = groupNames;
        this.bots = bots;
        this.history = history;
        this.instances = instances;
        this.qrCodes = qrCodes;
        this.chats = chats;
        this.deliveries = deliveries;
    }

    /** Uma linha da lista antes do filtro: a organização e o id decidem quem a vê. */
    record ScopedRow(UUID organizationId, UUID instanceId, InstanceRow row) {}

    /**
     * Todas as linhas, sem filtro. O SSE monta uma vez por envio e filtra por inscrito
     * ({@link #visible}). A saúde das entregas sai de uma consulta só, e não de uma por linha.
     */
    List<ScopedRow> allRows() {
        var failing = deliveries.failingWebhooks();
        return instances.list().stream()
                .map(i -> new ScopedRow(i.getOrganizationId(), i.getId(),
                        InstanceRow.of(i, instances.stateOf(i), failing.contains(i.getName()))))
                .toList();
    }

    /** As linhas que a pessoa vê ("ver status"), cada uma sabendo se ela pode conectar. */
    public List<InstanceRow> rows(AccessCheck access) {
        return visible(allRows(), access);
    }

    static List<InstanceRow> visible(List<ScopedRow> all, AccessCheck access) {
        return all.stream()
                .filter(r -> access.can(Permission.INSTANCE_VIEW, r.organizationId(), r.instanceId()))
                .map(r -> r.row().withConnect(access.can(Permission.INSTANCE_CONNECT, r.organizationId(), r.instanceId())))
                .toList();
    }

    public Live live(String name) {
        return live(name, true);
    }

    /** {@code withCode}: a pessoa pode conectar; sem isso, o trecho leva só o estado. */
    public Live live(String name, boolean withCode) {
        var instance = instances.get(name);
        var state = instances.stateOf(instance).value();
        var qr = qrCodes.get(name).orElse(null);
        var live = qr == null || "open".equals(state)
                ? Live.withoutCode(name, state)
                : Live.withCode(name, state, qr.code(), qr.base64(), qr.pairingCode(), qr.updatedAt());
        return withCode ? live : live.hidingCode();
    }

    /** O webhook desta instância tem entrega à espera de nova tentativa (aviso da visão geral)? */
    public boolean deliveryFailing(String name) {
        return deliveries.failingWebhooks().contains(name);
    }

    public Instance instance(String name) {
        return instances.get(name);
    }

    /** As conversas divididas entre pessoas e grupos; o nome do grupo vem do motor ({@link GroupNames}). */
    public ManagerViews.ChatList chatRows(String name) {
        var summaries = chats.findChats(name);
        var names = summaries.stream().anyMatch(c -> ManagerViews.isGroup(c.remoteJid()))
                ? groupNames.of(name) : java.util.Map.<String, String>of();
        return ManagerViews.ChatList.of(summaries.stream()
                .map(c -> {
                    var last = c.lastMessage();
                    var group = ManagerViews.isGroup(c.remoteJid());
                    // Em grupo, o pushName gravado é de quem mandou, não o nome do grupo
                    var chatName = group ? names.get(c.remoteJid()) : c.pushName();
                    var author = group && !last.key().fromMe()
                            ? firstNonBlank(last.pushName(), ManagerViews.phoneOrJid(last.key().participant())) : null;
                    var content = MessageContent.of(last.messageType(), last.message());
                    return new ChatRow(c.remoteJid(), chatName, content,
                            ManagerViews.time(last.messageTimestamp()), last.key().fromMe(), author,
                            reactedTo(name, c.remoteJid(), content));
                })
                .toList());
    }

    /** A mensagem alvo, quando a última da conversa é uma reação (uma consulta pelo id). */
    private MessageContent reactedTo(String name, String jid, MessageContent last) {
        if (!(last instanceof MessageContent.Reaction reaction) || reaction.removed() || reaction.targetId() == null) {
            return null;
        }
        return chats.findMessages(name, new KeyFilter(jid, null, reaction.targetId()), 1, 1).records().stream()
                .findFirst().map(m -> MessageContent.of(m.messageType(), m.message())).orElse(null);
    }

    public Conversation conversation(String name, String jid, int page) {
        var current = Math.max(page, 1);
        var found = chats.findMessages(name, new KeyFilter(jid, null, null), current, CONVERSATION_PAGE_SIZE);
        // Quem enviou: uma consulta para a página toda (fora do contrato do findMessages)
        var sentBy = history.sentBy(instances.get(name).getId(), jid, found.records().stream()
                .filter(m -> m.key().fromMe()).map(m -> m.key().id()).toList());
        var ordered = new ArrayList<>(found.records().stream()
                .map(m -> {
                    var content = MessageContent.of(m.messageType(), m.message());
                    return new ManagerViews.StoredRow(m.key().id(), m.key().fromMe(), m.pushName(), m.key().participant(),
                            content, m.messageTimestamp(), m.status(), mediaReady(name, jid, m, content),
                            m.key().fromMe() ? sentBy.get(m.key().id()) : null);
                })
                .toList());
        Collections.reverse(ordered);   // a página vem da mais nova para a mais antiga
        var group = ManagerViews.isGroup(jid);
        var chatName = group ? groupNames.of(name).get(jid)
                : found.records().stream().filter(m -> !m.key().fromMe()).map(m -> m.pushName())
                        .filter(n -> n != null && !n.isBlank()).findFirst().orElse(null);
        var bot = bots.state(name, jid).map(ManagerViews.BotChip::of).orElse(null);
        return new Conversation(jid, chatName, ManagerViews.rows(ordered, group, java.time.LocalDate.now()),
                current, found.pages(), bot);
    }

    /**
     * A mídia tem como ser baixada: o histórico guardou os campos de mídia ou o motor ainda tem a
     * mensagem na memória. Sem nenhum dos dois (mensagem antiga, depois de reiniciar), a tela mostra o
     * aviso do tipo direto, sem um player que não toca.
     */
    private boolean mediaReady(String name, String jid, dev.msgforge.chat.ChatDtos.MessageRecord m,
                               MessageContent content) {
        if (!content.media()) {
            return false;
        }
        return MessageContent.stored(m.messageType(), m.message()).isPresent()
                || chats.hasMedia(name, new dev.msgforge.gateway.WhatsAppGateway.MessageKey(jid, m.key().id(), m.key().fromMe()));
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
