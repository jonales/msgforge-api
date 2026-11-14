package dev.msgforge.web;

import dev.msgforge.chat.ChatDtos.KeyFilter;
import dev.msgforge.chat.ChatService;
import dev.msgforge.event.outbox.DeliveryHealth;
import dev.msgforge.instance.InstanceService;
import dev.msgforge.web.ManagerViews.ChatRow;
import dev.msgforge.web.ManagerViews.Conversation;
import dev.msgforge.web.ManagerViews.InstanceRow;
import dev.msgforge.web.ManagerViews.Live;
import dev.msgforge.web.ManagerViews.MessageRow;
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

    public ManagerScreens(InstanceService instances, QrCodeCache qrCodes, ChatService chats,
                          DeliveryHealth deliveries) {
        this.instances = instances;
        this.qrCodes = qrCodes;
        this.chats = chats;
        this.deliveries = deliveries;
    }

    /** As linhas da lista. A saúde das entregas sai de uma consulta só, e não de uma por linha. */
    public List<InstanceRow> rows() {
        var failing = deliveries.failingWebhooks();
        return instances.list().stream()
                .map(i -> InstanceRow.of(i, instances.stateOf(i), failing.contains(i.getName())))
                .toList();
    }

    public Live live(String name) {
        var instance = instances.get(name);
        var state = instances.stateOf(instance).value();
        var qr = qrCodes.get(name).orElse(null);
        return qr == null || "open".equals(state)
                ? Live.withoutCode(name, state)
                : Live.withCode(name, state, qr.code(), qr.base64(), qr.pairingCode(), qr.updatedAt());
    }

    public List<ChatRow> chatRows(String name) {
        return chats.findChats(name).stream()
                .map(c -> new ChatRow(c.remoteJid(), c.pushName(),
                        ManagerViews.preview(c.lastMessage().messageType(), c.lastMessage().message()),
                        ManagerViews.time(c.lastMessage().messageTimestamp()), c.lastMessage().key().fromMe()))
                .toList();
    }

    public Conversation conversation(String name, String jid, int page) {
        var current = Math.max(page, 1);
        var found = chats.findMessages(name, new KeyFilter(jid, null, null), current, CONVERSATION_PAGE_SIZE);
        var rows = new ArrayList<>(found.records().stream()
                .map(m -> new MessageRow(m.key().id(), m.key().fromMe(), m.pushName(),
                        ManagerViews.preview(m.messageType(), m.message()), ManagerViews.time(m.messageTimestamp()),
                        m.status()))
                .toList());
        Collections.reverse(rows);   // a página vem da mais nova para a mais antiga
        return new Conversation(jid, rows, current, found.pages());
    }
}
