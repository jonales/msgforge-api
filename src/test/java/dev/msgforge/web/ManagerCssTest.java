package dev.msgforge.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * O CSS do Manager é um arquivo só, sem build. Uma chave aberta a mais (como a de um seletor
 * duplicado num merge) aninha todo o resto do arquivo dentro de uma regra e desliga o estilo das
 * telas sem erro nenhum no console.
 */
class ManagerCssTest {

    private static final Path CSS = Path.of("src/main/resources/static/manager/manager.css");

    @Test
    void bracesAreBalanced() throws IOException {
        String css = Files.readString(CSS, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "");
        int depth = 0;
        int line = 1;
        for (char c : css.toCharArray()) {
            if (c == '\n') {
                line++;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                assertThat(depth).as("chave fechada sem abrir na linha %d", line).isGreaterThanOrEqualTo(0);
            }
        }
        assertThat(depth).as("chaves abertas sem fechar no manager.css").isZero();
    }

    @Test
    void noSelectorLineOpensInsideAnotherRuleByAccident() throws IOException {
        List<String> lines = Files.readAllLines(CSS, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String previous = lines.get(i - 1).strip();
            String current = lines.get(i).strip();
            boolean previousOpens = previous.endsWith("{") && !previous.startsWith("@");
            boolean currentOpens = current.endsWith("{") && !current.startsWith("@");
            assertThat(previousOpens && currentOpens)
                    .as("linhas %d e %d abrem duas regras seguidas (seletor duplicado?)", i, i + 1)
                    .isFalse();
        }
    }
}
