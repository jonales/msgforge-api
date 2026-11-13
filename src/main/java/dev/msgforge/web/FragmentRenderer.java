package dev.msgforge.web;

import jakarta.servlet.ServletContext;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.context.IWebContext;
import org.thymeleaf.linkbuilder.StandardLinkBuilder;

/**
 * Renderiza um trecho ({@code th:fragment}) dos templates do Manager fora de uma requisição, para o
 * SSE (ADR 0013). Fora da requisição não há {@code IWebContext}; o {@link Links} completa os links
 * {@code @{/...}} com o context path da aplicação.
 */
@Component
public class FragmentRenderer {

    static final String CONTEXT_PATH = "msgforgeContextPath";
    private static final Locale LOCALE = Locale.forLanguageTag("pt-BR");

    private final ITemplateEngine engine;
    private final String contextPath;

    public FragmentRenderer(ITemplateEngine engine, ServletContext servletContext) {
        this.engine = engine;
        this.contextPath = servletContext.getContextPath();
    }

    /** {@code render("manager/instance", "live", model)} equivale à view {@code "manager/instance :: live"}. */
    public String render(String template, String fragment, Map<String, Object> model) {
        var context = new Context(LOCALE, model);
        context.setVariable(CONTEXT_PATH, contextPath);
        return engine.process(template, Set.of(fragment), context);
    }

    /** Links de contexto ({@code @{/manager/...}}) também quando o contexto não é web. */
    static final class Links extends StandardLinkBuilder {

        @Override
        protected String computeContextPath(IExpressionContext context, String base, Map<String, Object> parameters) {
            if (!(context instanceof IWebContext) && context.getVariable(CONTEXT_PATH) instanceof String path) {
                return path;
            }
            return super.computeContextPath(context, base, parameters);
        }
    }

    /** Instala o {@link Links} no motor do Thymeleaf antes do primeiro uso (nas views, nada muda). */
    @Component
    static final class LinksInstaller implements BeanPostProcessor {

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof TemplateEngine engine && !engine.isInitialized()) {
                engine.setLinkBuilder(new Links());
            }
            return bean;
        }
    }
}
