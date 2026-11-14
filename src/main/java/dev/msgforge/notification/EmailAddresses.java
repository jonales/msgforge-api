package dev.msgforge.notification;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

/** Conferência dos endereços de e-mail antes de entrarem na fila. */
public final class EmailAddresses {

    /** Limite do SMTP para um endereço (RFC 5321); também cabe na chave de ordem da outbox. */
    static final int MAX_LENGTH = 254;

    private EmailAddresses() {}

    /** Endereço inválido: a mensagem diz o motivo, sem repetir o endereço. */
    public static final class InvalidEmailException extends IllegalArgumentException {
        public InvalidEmailException(String message) {
            super(message);
        }
    }

    /**
     * Um endereço só, sem nome ({@code ana@exemplo.com}, e não {@code Ana <ana@exemplo.com>}), sem
     * quebra de linha (que abriria espaço para injetar cabeçalhos) e no formato estrito da RFC 822.
     *
     * @return o endereço sem espaços nas pontas
     */
    public static String normalize(String email) {
        if (email == null || email.isBlank()) {
            throw new InvalidEmailException("e-mail vazio");
        }
        var trimmed = email.strip();
        if (trimmed.length() > MAX_LENGTH) {
            throw new InvalidEmailException("e-mail com mais de " + MAX_LENGTH + " caracteres");
        }
        if (trimmed.chars().anyMatch(c -> c < 0x20 || c == 0x7f || c == ' ' || c == '<' || c == ',')) {
            throw new InvalidEmailException("e-mail com caractere inválido");
        }
        try {
            var address = new InternetAddress(trimmed, true);
            address.validate();
            if (!trimmed.equals(address.getAddress()) || trimmed.indexOf('@') <= 0) {
                throw new InvalidEmailException("e-mail inválido");
            }
            return trimmed;
        } catch (AddressException e) {
            throw new InvalidEmailException("e-mail inválido");
        }
    }
}
