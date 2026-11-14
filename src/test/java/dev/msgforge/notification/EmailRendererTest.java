package dev.msgforge.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/** Modelos de e-mail: HTML e texto, com os textos do messages.properties. */
class EmailRendererTest {

    private static EmailRenderer renderer() {
        var messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        return new EmailRenderer(messages);
    }

    @Test
    void testEmailHasSubjectHtmlAndText() {
        var email = renderer().render(Notification.test(" ana@exemplo.com "));

        assertThat(email.to()).isEqualTo("ana@exemplo.com");
        assertThat(email.subject()).isEqualTo("E-mail de teste do Msgforge");
        assertThat(email.html()).contains("<h1", "O e-mail do Msgforge está funcionando", "lang=\"pt-BR\"")
                .doesNotContain("??");
        assertThat(email.text()).startsWith("O e-mail do Msgforge está funcionando")
                .contains("tela Servidor do Manager").doesNotContain("<", "??");
    }

    @Test
    void everyKindHasItsTemplatesAndSubject() {
        var renderer = renderer();
        for (var kind : Notification.Kind.values()) {
            var email = renderer.render(new Notification(kind, "ana@exemplo.com", null));
            assertThat(email.subject()).isNotBlank();
            assertThat(email.html()).isNotBlank().doesNotContain("??");
            assertThat(email.text()).isNotBlank().doesNotContain("??");
        }
    }
}
