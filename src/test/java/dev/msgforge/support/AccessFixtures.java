package dev.msgforge.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Limpeza do modelo de acesso entre testes: o {@code TRUNCATE instance CASCADE} da base não alcança
 * usuários e papéis. Volta ao estado da migration (organização padrão com os papéis-modelo).
 */
public final class AccessFixtures {

    public static final UUID DEFAULT_ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID GESTOR = UUID.fromString("00000000-0000-0000-0001-000000000001");
    public static final UUID OPERADOR = UUID.fromString("00000000-0000-0000-0001-000000000002");
    public static final UUID ATENDENTE = UUID.fromString("00000000-0000-0000-0001-000000000003");
    public static final UUID CLIENTE = UUID.fromString("00000000-0000-0000-0001-000000000004");

    private AccessFixtures() {}

    public static void reset(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM membership");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM role WHERE organization_id = ? AND id NOT IN (?, ?, ?, ?)",
                DEFAULT_ORG, GESTOR, OPERADOR, ATENDENTE, CLIENTE);
        jdbc.update("DELETE FROM instance WHERE organization_id <> ?", DEFAULT_ORG);
        jdbc.update("DELETE FROM organization WHERE id <> ?", DEFAULT_ORG);
    }
}
