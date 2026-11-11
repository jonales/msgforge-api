package dev.msgforge.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

    /**
     * Cliente usado para disparar webhooks. Com virtual threads, cada envio bloqueante é barato.
     * HTTP/1.1 fixo: o JDK tenta upgrade para h2c em http://, o que alguns receptores
     * (PHP, servidores simples) não entendem.
     */
    @Bean
    RestClient webhookRestClient(RestClient.Builder builder, MsgforgeProperties properties) {
        return builder
                .requestFactory(requestFactory(properties.webhook().requestTimeoutMs()))
                .defaultHeader("User-Agent", "Msgforge-API")
                .build();
    }

    public static JdkClientHttpRequestFactory requestFactory(long timeoutMs) {
        var timeout = Duration.ofMillis(timeoutMs);
        var client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(timeout);
        return factory;
    }
}
