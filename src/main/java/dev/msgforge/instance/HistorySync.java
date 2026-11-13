package dev.msgforge.instance;

/**
 * Quanto do histórico das conversas a instância pede ao WhatsApp no pareamento (QR ou código).
 * Só vale no próximo pareamento: uma sessão já conectada não recebe o histórico de novo.
 */
public enum HistorySync {
    /** Nenhum histórico: a conexão fica mais leve e o login, mais rápido. Padrão das novas instâncias. */
    NONE,
    /** O histórico recente que o WhatsApp manda por padrão (limitado pela configuração do celular). */
    RECENT,
    /** Até 365 dias de histórico: mais memória e mais rede no pareamento. */
    FULL;

    /** Valor do formulário; desconhecido ou vazio vira o padrão. */
    public static HistorySync parse(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
