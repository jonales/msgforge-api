package dev.msgforge.message;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.message.MessageDtos.ContactEntry;
import org.junit.jupiter.api.Test;

class VCardsTest {

    @Test
    void buildsAWhatsAppCardWithWaid() {
        var card = VCards.of(new ContactEntry("Fulano de Tal", null, "+55 (61) 99999-9999", "ACME", "f@acme.test",
                "https://acme.test"));

        assertThat(card).isEqualTo("""
                BEGIN:VCARD
                VERSION:3.0
                FN:Fulano de Tal
                ORG:ACME;
                TEL;type=CELL;type=VOICE;waid=5561999999999:+5561999999999
                EMAIL:f@acme.test
                URL:https://acme.test
                END:VCARD""");
    }

    @Test
    void explicitWuidWinsAndOptionalFieldsAreOmitted() {
        var card = VCards.of(new ContactEntry("Ana", "5561988887777", "61 98888-7777", null, " ", null));
        assertThat(card).contains("waid=5561988887777:+61988887777").doesNotContain("ORG").doesNotContain("EMAIL")
                .doesNotContain("URL");
    }

    @Test
    void valuesCannotInjectNewLinesOrFields() {
        var card = VCards.of(new ContactEntry("Mallory\nTEL;waid=999:+999", null, "5561999999999",
                "A;B,C\\D", null, null));

        assertThat(card.lines()).filteredOn(l -> l.startsWith("TEL")).hasSize(1);
        assertThat(card).contains("FN:Mallory\\nTEL\\;waid=999:+999")
                .contains("ORG:A\\;B\\,C\\\\D;");
    }
}
