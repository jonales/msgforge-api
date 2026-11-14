package dev.msgforge.access;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
class AccessConfig {

    /**
     * {@code DelegatingPasswordEncoder} (ADR 0010): grava {@code {bcrypt}...} e continua lendo
     * hashes de outro algoritmo, se um dia o padrão mudar (Argon2, por exemplo).
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
