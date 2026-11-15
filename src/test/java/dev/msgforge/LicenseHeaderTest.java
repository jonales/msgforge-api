// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Todo arquivo Java começa com a licença (ADR 0016): sem ela, o arquivo novo falha aqui. */
class LicenseHeaderTest {

    @Test
    void everyJavaFileStartsWithTheSpdxLicense() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src"))) {
            var missing = files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !startsWithLicense(f))
                    .map(Path::toString)
                    .toList();
            assertThat(missing).as("arquivos sem // SPDX-License-Identifier: AGPL-3.0-only").isEmpty();
        }
    }

    private static boolean startsWithLicense(Path file) {
        try (var lines = Files.lines(file)) {
            return lines.findFirst().orElse("").equals("// SPDX-License-Identifier: AGPL-3.0-only");
        } catch (IOException e) {
            return false;
        }
    }
}
