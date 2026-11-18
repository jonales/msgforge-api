// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.access;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Política de senha do Manager (ADR 0017), pelo NIST SP 800-63B rev. 4 e pela OWASP:
 * <ul>
 *   <li>mínimo de 15 caracteres: a senha é o único fator ("SHALL require passwords that are used as a
 *       single-factor authentication mechanism to be a minimum of 15 characters");</li>
 *   <li>máximo de 72 bytes: o limite do bcrypt, que ignora o que passa disso. Cabem os 64 caracteres
 *       que o NIST pede para aceitar ("SHOULD permit ... at least 64"), menos com muitos acentos;</li>
 *   <li>sem regras de composição ("SHALL NOT impose other composition rules"): qualquer caractere,
 *       inclusive espaço e Unicode;</li>
 *   <li>lista de bloqueio ("commonly used, expected, or compromised values"): senhas comuns de 15+
 *       caracteres (o dicionário "só precisa ter entradas que cumpram o tamanho mínimo"), repetição,
 *       sequência e palavras do contexto (o nome do serviço, o e-mail).</li>
 * </ul>
 * Regra pura: não guarda nem registra a senha. A tela mede o mesmo no navegador, mas quem decide é aqui.
 */
public final class PasswordPolicy {

    public static final int MIN = 15;
    public static final int MAX_BYTES = 72;

    /** O que está errado, na ordem em que a pessoa deve corrigir. */
    public enum Problem { SHORT, LONG, REPETITIVE, SEQUENCE, CONTEXT, COMMON }

    private static final String[] SEQUENCES = {
        "abcdefghijklmnopqrstuvwxyz", "0123456789", "qwertyuiopasdfghjklçzxcvbnm", "qwertyuiopasdfghjklzxcvbnm",
        "1qaz2wsx3edc4rfv5tgb6yhn7ujm8ik9ol0p", "qazwsxedcrfvtgbyhnujmikolp"};
    private static final Set<String> COMMON = load();

    private PasswordPolicy() {}

    /**
     * O primeiro problema da senha, ou vazio se ela pode ser usada. {@code context}: palavras que não
     * podem ser a base da senha (e-mail, nome de exibição); o nome do serviço entra sempre.
     */
    public static Optional<Problem> check(String password, String... context) {
        if (password == null || password.codePointCount(0, password.length()) < MIN) {
            return Optional.of(Problem.SHORT);
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Optional.of(Problem.LONG);
        }
        var plain = plain(password);
        if (repetitive(plain)) {
            return Optional.of(Problem.REPETITIVE);
        }
        if (sequence(plain)) {
            return Optional.of(Problem.SEQUENCE);
        }
        if (mostlyContext(plain, context)) {
            return Optional.of(Problem.CONTEXT);
        }
        if (COMMON.contains(plain)) {
            return Optional.of(Problem.COMMON);
        }
        return Optional.empty();
    }

    /** Minúsculas e sem espaço: "Minha Senha" e "minhasenha" são a mesma escolha. */
    static String plain(String password) {
        return password.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /** Poucos caracteres diferentes ("aaaaaaa...", "abababab...") ou um pedaço curto repetido. */
    static boolean repetitive(String plain) {
        if (plain.codePoints().distinct().count() <= 3) {
            return true;
        }
        return plain.matches("(.{1,7})\\1+.{0,3}");
    }

    /** A senha (ou quase toda ela) é um trecho de uma sequência conhecida, em qualquer sentido. */
    static boolean sequence(String plain) {
        for (var base : SEQUENCES) {
            var loop = base.repeat(3);
            var reverse = new StringBuilder(loop).reverse().toString();
            for (int cut = 0; cut <= 2 && plain.length() - cut >= MIN - 2; cut++) {
                var core = plain.substring(0, plain.length() - cut);
                if (loop.contains(core) || reverse.contains(core)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Sem as palavras do contexto (e o nome do serviço), sobra menos que o mínimo. */
    static boolean mostlyContext(String plain, String... context) {
        var rest = plain;
        var words = Arrays.stream(context == null ? new String[0] : context)
                .filter(w -> w != null && !w.isBlank())
                .flatMap(w -> Arrays.stream(plain(w).split("[@._+\\-]")))
                .filter(w -> w.length() >= 4)
                .collect(Collectors.toSet());
        words.add("msgforge");
        for (var word : words) {
            rest = rest.replace(word, "");
        }
        return rest.length() < MIN - 5 && rest.length() < plain.length();
    }

    private static Set<String> load() {
        try (InputStream in = PasswordPolicy.class.getResourceAsStream("/security/senhas-comuns.txt")) {
            if (in == null) {
                throw new IllegalStateException("security/senhas-comuns.txt não está no classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .map(PasswordPolicy::plain)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
