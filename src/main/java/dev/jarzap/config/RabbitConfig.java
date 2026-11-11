package dev.jarzap.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exchange tópico; routing key = "{instancia}.{evento}" (ex.: "vendas.messages.upsert").
 * Consumidores ligam filas com padrões como "*.messages.upsert" ou "vendas.#".
 */
@Configuration
@ConditionalOnProperty(name = "jarzap.rabbitmq.enabled", havingValue = "true")
public class RabbitConfig {

    @Bean
    TopicExchange jarzapExchange(JarZapProperties properties) {
        return new TopicExchange(properties.rabbitmq().exchange(), true, false);
    }
}
