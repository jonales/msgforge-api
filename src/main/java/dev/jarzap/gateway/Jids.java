package dev.jarzap.gateway;

/** Normaliza "number" da Evolution (5561999999999, grupo ou JID completo) para JID. */
public final class Jids {

    private Jids() {}

    public static String toJid(String number) {
        if (number == null || number.isBlank()) {
            throw new IllegalArgumentException("number obrigatório");
        }
        var n = number.trim();
        if (n.contains("@")) {
            return n;
        }
        // Grupo: ID antigo com hífen (5561999999999-1612345678) ou novo com 18 dígitos (1203...)
        if (n.matches("\\d+-\\d+") || n.matches("\\d{18,}")) {
            return n + "@g.us";
        }
        // Telefone (E.164 tem no máximo 15 dígitos): remove +, espaços, parênteses e hífens
        return n.replaceAll("\\D", "") + "@s.whatsapp.net";
    }
}
