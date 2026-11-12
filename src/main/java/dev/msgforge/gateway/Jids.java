package dev.msgforge.gateway;

import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import java.util.Set;
import java.util.regex.Pattern;

/** Normaliza o campo "number" da API (5561999999999, grupo ou JID completo) para JID. */
public final class Jids {

    private static final Set<String> SERVERS =
            Set.of("s.whatsapp.net", "g.us", "lid", "broadcast", "newsletter", "c.us");

    /** Só dígitos e a pontuação comum de telefone: "+55 (61) 99999-9999". */
    private static final Pattern PHONE = Pattern.compile("\\+?[\\d\\s().-]+");
    private static final Pattern OLD_GROUP = Pattern.compile("\\d+-\\d+");
    private static final Pattern NEW_GROUP = Pattern.compile("\\d{18,}");
    private static final Pattern JID_USER = Pattern.compile("[\\w.:-]+");

    private Jids() {}

    /** @throws BadRequestException (HTTP 400) quando o valor não é telefone, grupo nem JID. */
    public static String toJid(String number) {
        if (number == null || number.isBlank()) {
            throw invalid(number);
        }
        var n = number.trim();
        if (n.contains("@")) {
            var at = n.indexOf('@');
            var user = n.substring(0, at);
            var server = n.substring(at + 1);
            if (!JID_USER.matcher(user).matches() || !SERVERS.contains(server)) {
                throw invalid(number);
            }
            return "c.us".equals(server) ? user + "@s.whatsapp.net" : n;
        }
        // Grupo: ID antigo com hífen (5561999999999-1612345678) ou novo com 18+ dígitos (1203...)
        if (OLD_GROUP.matcher(n).matches() || NEW_GROUP.matcher(n).matches()) {
            return n + "@g.us";
        }
        if (!PHONE.matcher(n).matches()) {
            throw invalid(number);
        }
        // E.164: no máximo 15 dígitos; menos de 8 não é um telefone completo
        var digits = n.replaceAll("\\D", "");
        if (digits.length() < 8 || digits.length() > 15) {
            throw invalid(number);
        }
        return digits + "@s.whatsapp.net";
    }

    /**
     * Celular brasileiro na outra forma: com nono dígito (55 + DDD + 9 + 8 dígitos) vira sem,
     * e vice-versa. O WhatsApp registrou parte das contas antigas sem o 9.
     */
    public static java.util.Optional<String> brazilianMobileAlternative(String jid) {
        if (jid == null || !jid.endsWith("@s.whatsapp.net")) {
            return java.util.Optional.empty();
        }
        var user = jid.substring(0, jid.indexOf('@'));
        if (!user.startsWith("55") || !user.chars().allMatch(Character::isDigit)) {
            return java.util.Optional.empty();
        }
        if (user.length() == 13 && user.charAt(4) == '9') {
            return java.util.Optional.of(user.substring(0, 4) + user.substring(5) + "@s.whatsapp.net");
        }
        // Sem o 9: só celulares (o número local começa com 6 a 9; fixos começam com 2 a 5)
        if (user.length() == 12 && user.charAt(4) >= '6') {
            return java.util.Optional.of(user.substring(0, 4) + "9" + user.substring(4) + "@s.whatsapp.net");
        }
        return java.util.Optional.empty();
    }

    private static BadRequestException invalid(String number) {
        return new BadRequestException("Invalid number: \"" + number + "\"");
    }
}
