-- V202611141330__adiciona_tipo_email_na_outbox
-- Motivo: notificações por e-mail (ADR 0011) entregues pela outbox (ADR 0012)
-- Lembretes: não edite depois do merge; atualize a entidade JPA no mesmo commit;
-- dê nome às constraints (pk_/fk_/uk_/ix_/ck_). Ver docs/MIGRATIONS.md.

-- Tipo EMAIL: a chave de ordem é o destinatário, o destino é o endereço e o payload é o e-mail
-- já montado (assunto, texto e HTML), sem credencial do SMTP. A outbox é tabela de SQL direto
-- (classe Outbox), sem entidade JPA.
ALTER TABLE outbox DROP CONSTRAINT ck_outbox_type;
ALTER TABLE outbox ADD CONSTRAINT ck_outbox_type
    CHECK (type IN ('WEBHOOK', 'WEBHOOK_GLOBAL', 'RABBITMQ', 'EMAIL'));
