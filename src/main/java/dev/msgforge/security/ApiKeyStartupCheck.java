package dev.msgforge.security;

import dev.msgforge.config.MsgforgeProperties;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Recusa subir com a chave global padrão, vazia ou curta: ela abre a API inteira e o Manager.
 * Só o perfil {@code dev} (SPRING_PROFILES_ACTIVE=dev, ativado pelo {@code mvn spring-boot:run})
 * aceita uma chave fraca, com um aviso no log.
 */
@Component
public class ApiKeyStartupCheck {

    static final String DEFAULT_KEY = "troque-esta-chave";
    static final int MIN_LENGTH = 32;
    static final String DEV_PROFILE = "dev";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyStartupCheck.class);

    public ApiKeyStartupCheck(MsgforgeProperties properties, Environment environment) {
        var problem = problem(properties.authentication().apiKey());
        if (problem.isEmpty()) {
            return;
        }
        if (environment.matchesProfiles(DEV_PROFILE)) {
            log.warn("Perfil dev: aceitando uma chave global fraca ({}). Fora do perfil dev a aplicação não sobe.",
                    problem.get());
            return;
        }
        throw new IllegalStateException("AUTHENTICATION_API_KEY inválida: " + problem.get()
                + ". Defina AUTHENTICATION_API_KEY com uma chave aleatória de pelo menos " + MIN_LENGTH
                + " caracteres (por exemplo, a saída de: openssl rand -hex 32). A chave padrão só é aceita"
                + " no perfil de desenvolvimento (SPRING_PROFILES_ACTIVE=dev).");
    }

    /** O que há de errado com a chave, ou vazio se ela serve fora do perfil dev. */
    static Optional<String> problem(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.of("a chave está vazia");
        }
        if (apiKey.strip().equals(DEFAULT_KEY)) {
            return Optional.of("é a chave padrão do exemplo");
        }
        if (apiKey.length() < MIN_LENGTH) {
            return Optional.of("a chave tem menos de " + MIN_LENGTH + " caracteres");
        }
        return Optional.empty();
    }
}
