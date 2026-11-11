package dev.jarzap.common;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RootController {

    @GetMapping("/")
    public Map<String, Object> root() {
        return Map.of(
                "status", 200,
                "message", "Welcome to JarZap API, it is working!",
                "version", "0.1.0",
                "documentation", "/docs");
    }
}
