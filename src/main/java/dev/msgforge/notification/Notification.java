package dev.msgforge.notification;

import java.util.Map;
import java.util.Objects;

/**
 * Uma notificação para uma pessoa (ADR 0011): o tipo escolhe o modelo, e as variáveis preenchem o
 * modelo. Nada de segredo nas variáveis além do que a própria mensagem precisa mostrar (um link de
 * convite, por exemplo): o conteúdo montado fica gravado na outbox até a entrega.
 *
 * @param kind      o tipo, que define o modelo e o assunto
 * @param email     endereço do destinatário, já conferido ({@link EmailAddresses#normalize})
 * @param variables valores usados no modelo
 */
public record Notification(Kind kind, String email, Map<String, Object> variables) {

    /**
     * Tipos de notificação. Cada um tem {@code templates/email/<template>.html} e {@code .txt}, e o
     * assunto em {@code email.<template>.subject} no {@code messages.properties}.
     */
    public enum Kind {
        /** "Enviar e-mail de teste" da tela Servidor do Manager. */
        TEST("test");

        private final String template;

        Kind(String template) {
            this.template = template;
        }

        public String template() {
            return template;
        }

        /** Nome do evento na outbox ({@code email.test}). */
        public String event() {
            return "email." + template;
        }
    }

    public Notification {
        Objects.requireNonNull(kind, "kind");
        email = EmailAddresses.normalize(email);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    public static Notification test(String email) {
        return new Notification(Kind.TEST, email, Map.of());
    }
}
