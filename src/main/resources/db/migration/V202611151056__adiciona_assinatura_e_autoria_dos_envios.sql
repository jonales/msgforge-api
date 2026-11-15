-- V202611151056__adiciona_assinatura_e_autoria_dos_envios
-- Motivo: assinatura do atendente nas respostas do Manager e registro de quem enviou cada mensagem.
-- display_name: o nome que assina (vazio = responde sem assinatura). sign_messages: liga a assinatura
-- por instância (configuração do Manager, fora do contrato da API). Na mensagem, sent_by diz por onde
-- ela saiu; o nome fica copiado em sent_by_name (o histórico mostra quem era na hora, mesmo que o
-- usuário mude de nome ou seja apagado). Mensagem que chegou pelo celular ou de outra pessoa: null.
-- Lembretes: não edite depois do merge; atualize a entidade JPA no mesmo commit;
-- dê nome às constraints (pk_/fk_/uk_/ix_/ck_). Ver docs/MIGRATIONS.md.

ALTER TABLE app_user ADD COLUMN display_name VARCHAR(60);

ALTER TABLE instance ADD COLUMN sign_messages BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE message ADD COLUMN sent_by VARCHAR(12);
ALTER TABLE message ADD COLUMN sent_by_user_id UUID;
ALTER TABLE message ADD COLUMN sent_by_name VARCHAR(254);
ALTER TABLE message ADD CONSTRAINT ck_message_sent_by
    CHECK (sent_by IN ('USER', 'MANAGER_KEY', 'API_KEY', 'API_TOKEN'));
ALTER TABLE message ADD CONSTRAINT fk_message_sent_by_user FOREIGN KEY (sent_by_user_id)
    REFERENCES app_user (id) ON DELETE SET NULL;
CREATE INDEX ix_message_sent_by_user ON message (sent_by_user_id) WHERE sent_by_user_id IS NOT NULL;
