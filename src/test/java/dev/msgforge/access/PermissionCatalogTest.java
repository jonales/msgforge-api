package dev.msgforge.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Uma permissão nova no catálogo exige texto na tela e uma migration que a aceite no CHECK de
 * {@code role_permission} (ADR 0010). Este teste lembra das duas coisas.
 */
class PermissionCatalogTest {

    @Test
    void everyPermissionHasALabelAndAHint() throws IOException {
        var messages = new Properties();
        try (Reader in = Files.newBufferedReader(Path.of("src/main/resources/messages.properties"), StandardCharsets.UTF_8)) {
            messages.load(in);
        }
        for (var permission : Permission.values()) {
            assertThat(messages).containsKey("manager.permission." + permission.name());
            assertThat(messages).containsKey("manager.permission." + permission.name() + ".hint");
        }
        for (var group : Permission.Group.values()) {
            assertThat(messages).containsKey("manager.permission.group." + group.name());
        }
    }

    @Test
    void everyPermissionIsAcceptedByAMigration() throws IOException {
        String sql;
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            sql = files.map(file -> {
                try {
                    return Files.readString(file);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).collect(Collectors.joining("\n"));
        }
        for (var permission : Permission.values()) {
            assertThat(sql).as("migration com '%s' no CHECK de role_permission", permission)
                    .contains("'" + permission.name() + "'");
        }
    }
}
