-- V202611112031__cria_tabela_message_para_historico
-- Motivo: cria tabela message para historico
-- Lembretes: não edite depois do merge; atualize a entidade JPA no mesmo commit;
-- dê nome às constraints (pk_/fk_/uk_/ix_/ck_). Ver docs/MIGRATIONS.md.

-- Histórico de mensagens enviadas e recebidas, para /chat/findMessages e /chat/findChats (ADR 0008).
-- A key do contrato é (remote_jid, key_id, from_me); a mesma key pode existir em instâncias diferentes.

CREATE TABLE message (
    id                UUID         NOT NULL,
    instance_id       UUID         NOT NULL,
    key_id            VARCHAR(128) NOT NULL,
    remote_jid        VARCHAR(128) NOT NULL,
    from_me           BOOLEAN      NOT NULL,
    participant       VARCHAR(128),
    push_name         VARCHAR(255),
    message_type      VARCHAR(64)  NOT NULL,
    message           JSONB        NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    message_timestamp BIGINT       NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_message PRIMARY KEY (id),
    CONSTRAINT fk_message_instance FOREIGN KEY (instance_id) REFERENCES instance (id) ON DELETE CASCADE,
    CONSTRAINT uk_message_key UNIQUE (instance_id, remote_jid, key_id, from_me),
    CONSTRAINT ck_message_status CHECK (status IN
        ('ERROR', 'PENDING', 'SERVER_ACK', 'DELIVERY_ACK', 'READ', 'PLAYED'))
);

-- Conversa em ordem cronológica (findMessages por remoteJid e findChats). Começa por instance_id,
-- então também serve de índice da FK.
CREATE INDEX ix_message_instance_chat_ts ON message (instance_id, remote_jid, message_timestamp DESC);
