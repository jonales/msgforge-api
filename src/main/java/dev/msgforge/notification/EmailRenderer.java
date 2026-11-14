package dev.msgforge.notification;

import java.util.Locale;
import java.util.Set;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Monta o e-mail de uma {@link Notification} a partir de {@code templates/email/<modelo>.html} e
 * {@code .txt}, com os textos do {@code messages.properties} (pt-BR, como o Manager).
 *
 * <p>Usa um motor Thymeleaf próprio, separado do que renderiza o Manager: o e-mail precisa do modo
 * TEXT para a versão texto, e o motor do Manager continua só com os modelos dele. O HTML do e-mail
 * pode ter estilo inline (os clientes de e-mail não leem CSS externo); o CSP do Manager não vale aqui.
 */
@Component
public class EmailRenderer {

    static final Locale LOCALE = Locale.forLanguageTag("pt-BR");

    private final SpringTemplateEngine engine = new SpringTemplateEngine();
    private final MessageSource messages;

    public EmailRenderer(MessageSource messages) {
        this.messages = messages;
        engine.addTemplateResolver(resolver(TemplateMode.HTML, ".html", 1));
        engine.addTemplateResolver(resolver(TemplateMode.TEXT, ".txt", 2));
        engine.setTemplateEngineMessageSource(messages);
    }

    private static ClassLoaderTemplateResolver resolver(TemplateMode mode, String suffix, int order) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/email/");
        resolver.setTemplateMode(mode);
        resolver.setResolvablePatterns(Set.of("*" + suffix));
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCheckExistence(true);
        resolver.setCacheable(true);
        resolver.setOrder(order);
        return resolver;
    }

    public EmailMessage render(Notification notification) {
        var template = notification.kind().template();
        var context = new Context(LOCALE, notification.variables());
        var subject = messages.getMessage("email." + template + ".subject", null, LOCALE);
        context.setVariable("subject", subject);
        return new EmailMessage(notification.email(), subject,
                engine.process(template + ".txt", context),
                engine.process(template + ".html", context));
    }
}
