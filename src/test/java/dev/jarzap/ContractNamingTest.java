package dev.jarzap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.jarzap.event.EventType;
import dev.jarzap.gateway.Jids;
import org.junit.jupiter.api.Test;

/** Garante que nomes e formatos batem com o que a Evolution envia/recebe. */
class ContractNamingTest {

    @Test
    void eventNamesMatchEvolutionPayload() {
        assertEquals("messages.upsert", EventType.MESSAGES_UPSERT.payloadName());
        assertEquals("/messages-upsert", EventType.MESSAGES_UPSERT.urlSuffix());
        assertEquals("connection.update", EventType.CONNECTION_UPDATE.payloadName());
        assertEquals("qrcode.updated", EventType.QRCODE_UPDATED.payloadName());
    }

    @Test
    void numbersBecomeJids() {
        assertEquals("5561999999999@s.whatsapp.net", Jids.toJid("5561999999999"));
        assertEquals("5561999999999@s.whatsapp.net", Jids.toJid("+55 (61) 99999-9999"));
        assertEquals("120363000000000000@g.us", Jids.toJid("120363000000000000"));
        assertEquals("120363000000000000@g.us", Jids.toJid("120363000000000000@g.us"));
        assertEquals("5561999999999-1612345678@g.us", Jids.toJid("5561999999999-1612345678"));
    }
}
