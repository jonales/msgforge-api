package dev.jarzap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JarZapApplication {

    public static void main(String[] args) {
        SpringApplication.run(JarZapApplication.class, args);
    }
}
