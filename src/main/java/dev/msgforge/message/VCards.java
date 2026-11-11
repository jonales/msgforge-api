package dev.msgforge.message;

import dev.msgforge.message.MessageDtos.ContactEntry;

/**
 * Monta o cartão (vCard 3.0) de um contato. Todo valor passa por escape (RFC 6350): sem isso,
 * uma quebra de linha no nome injetaria linhas novas no cartão.
 */
final class VCards {

    private VCards() {}

    static String of(ContactEntry contact) {
        var digits = contact.phoneNumber().replaceAll("\\D", "");
        var wuid = contact.wuid() != null && !contact.wuid().isBlank()
                ? contact.wuid().replaceAll("\\D", "")
                : digits;
        var card = new StringBuilder()
                .append("BEGIN:VCARD\n")
                .append("VERSION:3.0\n")
                .append("FN:").append(escape(contact.fullName())).append('\n');
        if (present(contact.organization())) {
            card.append("ORG:").append(escape(contact.organization())).append(";\n");
        }
        // waid = número no WhatsApp: é o que faz o cartão abrir a conversa no app
        card.append("TEL;type=CELL;type=VOICE;waid=").append(wuid).append(":+").append(digits).append('\n');
        if (present(contact.email())) {
            card.append("EMAIL:").append(escape(contact.email())).append('\n');
        }
        if (present(contact.url())) {
            card.append("URL:").append(escape(contact.url())).append('\n');
        }
        return card.append("END:VCARD").toString();
    }

    static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
