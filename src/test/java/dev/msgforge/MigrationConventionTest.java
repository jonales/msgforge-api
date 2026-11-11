package dev.msgforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Garante o padrão de nomes de docs/MIGRATIONS.md. */
class MigrationConventionTest {

    private static final Path DIR = Path.of("src/main/resources/db/migration");

    /** Linha de base criada antes do padrão; não pode ser renomeada (checksum/histórico). */
    private static final String BASELINE = "V1__init.sql";

    private static final Pattern VERSIONED = Pattern.compile("V(\\d{12})__[a-z0-9]+(_[a-z0-9]+)*\\.sql");
    private static final Pattern REPEATABLE = Pattern.compile("R__[a-z0-9]+(_[a-z0-9]+)*\\.sql");

    private static List<String> migrations() throws IOException {
        try (Stream<Path> files = Files.list(DIR)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void everyMigrationFollowsTheNamingPattern() throws IOException {
        var invalid = migrations().stream()
                .filter(n -> !n.equals(BASELINE))
                .filter(n -> !VERSIONED.matcher(n).matches() && !REPEATABLE.matcher(n).matches())
                .toList();
        assertEquals(List.of(), invalid,
                "Use scripts/new-migration.sh: V{yyyyMMddHHmm}__descricao.sql ou R__descricao.sql");
    }

    @Test
    void versionsAreUniqueAndTimestampsAreValid() throws IOException {
        var seen = new HashSet<String>();
        for (var name : migrations()) {
            var m = VERSIONED.matcher(name);
            if (!m.matches()) {
                continue;
            }
            var version = m.group(1);
            assertTrue(seen.add(version), "Versão duplicada: " + version);
            int month = Integer.parseInt(version.substring(4, 6));
            int day = Integer.parseInt(version.substring(6, 8));
            int hour = Integer.parseInt(version.substring(8, 10));
            int minute = Integer.parseInt(version.substring(10, 12));
            assertTrue(month >= 1 && month <= 12 && day >= 1 && day <= 31 && hour < 24 && minute < 60,
                    "Timestamp inválido em " + name);
        }
    }

    @Test
    void migrationsAreNotEmpty() throws IOException {
        for (var name : migrations()) {
            var sql = Files.readString(DIR.resolve(name)).lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("--"))
                    .toList();
            assertFalse(sql.isEmpty(), "Migration sem SQL: " + name);
        }
    }
}
