package dev.msgforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MsgforgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsgforgeApplication.class, args);
    }
}
