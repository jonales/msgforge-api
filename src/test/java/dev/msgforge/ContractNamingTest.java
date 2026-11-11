package dev.msgforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.msgforge.common.ApiExceptionHandler.BadRequestException;

import dev.msgforge.event.EventType;
import dev.msgforge.gateway.Jids;
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

    @Test
    void jidsAcceptKnownServersAndNormalizeCUs() {
        assertEquals("5561999999999@s.whatsapp.net", Jids.toJid("5561999999999@c.us"));
        assertEquals("123456789@lid", Jids.toJid("123456789@lid"));
        assertEquals("status@broadcast", Jids.toJid("status@broadcast"));
    }

    @Test
    void invalidNumbersAreRejected() {
        for (var invalid : new String[] {null, "", "  ", "abc", "55abc61", "1234567", "1234567890123456",
                "5561999999999@gmail.com", "@s.whatsapp.net"}) {
            assertThrows(BadRequestException.class, () -> Jids.toJid(invalid), "aceitou: " + invalid);
        }
    }
}
