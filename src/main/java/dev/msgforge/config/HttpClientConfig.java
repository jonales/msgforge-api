package dev.msgforge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

    /** Cliente usado para disparar webhooks. Com virtual threads, cada envio bloqueante é barato. */
    @Bean
    RestClient webhookRestClient(RestClient.Builder builder) {
        return builder.defaultHeader("User-Agent", "Msgforge-API").build();
    }
}
