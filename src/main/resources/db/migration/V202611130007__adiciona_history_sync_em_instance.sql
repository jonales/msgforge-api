-- V202611130007__adiciona_history_sync_em_instance
-- Motivo: histórico das conversas pedido no pareamento, escolhido por instância no Manager.
-- NONE = sem histórico (conexão mais leve), RECENT = o recente que o WhatsApp manda por padrão,
-- FULL = até 365 dias. Vale no próximo pareamento (QR ou código).
-- As instâncias que já existem ficam em RECENT, o comportamento de antes; as novas nascem em NONE.

ALTER TABLE instance ADD COLUMN history_sync VARCHAR(10) NOT NULL DEFAULT 'RECENT';
ALTER TABLE instance ALTER COLUMN history_sync SET DEFAULT 'NONE';
ALTER TABLE instance ADD CONSTRAINT ck_instance_history_sync
    CHECK (history_sync IN ('NONE', 'RECENT', 'FULL'));
