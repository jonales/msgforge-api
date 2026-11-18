// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.access;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.access.PasswordPolicy.Problem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Política de senha (ADR 0017): o que cai, por quê, e o que não pode cair por engano. */
class PasswordPolicyTest {

    @Test
    void longPassphrasesWithSpacesAccentsAndNoSymbolsAreAccepted() {
        assertThat(PasswordPolicy.check("cavalo correto bateria grampo")).isEmpty();
        assertThat(PasswordPolicy.check("pão de queijo com café quente")).isEmpty();
        assertThat(PasswordPolicy.check("senha-inicial-123")).isEmpty();   // a dos testes de integração
        // sem exigir maiúscula, número ou símbolo (NIST: SHALL NOT impose other composition rules)
        assertThat(PasswordPolicy.check("minha bicicleta azul anda devagar")).isEmpty();
    }

    @Test
    void lengthIsAtLeastFifteenCharactersAndAtMostSeventyTwoBytes() {
        assertThat(PasswordPolicy.check(null)).contains(Problem.SHORT);
        assertThat(PasswordPolicy.check("frase curta 12")).contains(Problem.SHORT);         // 14
        assertThat(PasswordPolicy.check("frase não tão curta")).isEmpty();                  // 19
        assertThat(PasswordPolicy.check("palavras diferentes ".repeat(4))).contains(Problem.LONG);   // 80 bytes
        assertThat(PasswordPolicy.check("ação ".repeat(15))).contains(Problem.LONG);        // 75 caracteres, 105 bytes
    }

    @Test
    void repetitionAndPredictableSequencesAreRefused() {
        assertThat(PasswordPolicy.check("aaaaaaaaaaaaaaaa")).contains(Problem.REPETITIVE);
        assertThat(PasswordPolicy.check("senhasenhasenha")).contains(Problem.REPETITIVE);
        assertThat(PasswordPolicy.check("abababababababab12")).contains(Problem.REPETITIVE);
        assertThat(PasswordPolicy.check("123456789012345")).contains(Problem.SEQUENCE);
        assertThat(PasswordPolicy.check("qwertyuiopasdfgh")).contains(Problem.SEQUENCE);
        assertThat(PasswordPolicy.check("zyxwvutsrqponmlk")).contains(Problem.SEQUENCE);
        assertThat(PasswordPolicy.check("1qaz2wsx3edc4rfv!")).contains(Problem.SEQUENCE);
    }

    @Test
    void aPasswordMadeMostlyOfTheEmailNameOrServiceIsRefused() {
        assertThat(PasswordPolicy.check("souzasouza12345", "ana.souza@exemplo.com")).contains(Problem.CONTEXT);
        assertThat(PasswordPolicy.check("Msgforge2026!!!")).contains(Problem.CONTEXT);
        // o nome dentro de uma frase longa é aceito: sobra frase de verdade
        assertThat(PasswordPolicy.check("a souza leva o cachorro para passear", "ana.souza@exemplo.com"))
                .isEmpty();
    }

    @Test
    void commonLongPasswordsAreRefusedIgnoringCaseAndSpaces() {
        assertThat(PasswordPolicy.check("Minha Senha Segura")).contains(Problem.COMMON);
        assertThat(PasswordPolicy.check("correcthorsebatterystaple")).contains(Problem.COMMON);
        assertThat(PasswordPolicy.check("BemVindo12345678")).contains(Problem.COMMON);
    }

    @Test
    void theBlocklistOnlyHoldsEntriesThatPassTheMinimumLength() throws IOException {
        // NIST: o dicionário só precisa ter entradas que cumpram o tamanho mínimo
        try (var in = PasswordPolicy.class.getResourceAsStream("/security/senhas-comuns.txt")) {
            assertThat(in).isNotNull();
            new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(l -> !l.isBlank() && !l.startsWith("#"))
                    .forEach(l -> assertThat(PasswordPolicy.plain(l).length())
                            .as("entrada curta na lista: %s", l).isGreaterThanOrEqualTo(PasswordPolicy.MIN));
        }
    }

    @Test
    void theServiceRefusesWithOneMessageKeyPerProblem() {
        assertThat(problemKey("curta")).isEqualTo("manager.access.error.password.short");
        assertThat(problemKey("123456789012345")).isEqualTo("manager.access.error.password.sequence");
        assertThat(problemKey("cavalo correto bateria grampo")).isNull();
    }

    private static String problemKey(String password) {
        try {
            AccessAdminService.checkPassword(password, "ana@exemplo.com", "Ana");
            return null;
        } catch (AccessAdminService.AccessRuleException e) {
            return e.getMessage();
        }
    }
}
