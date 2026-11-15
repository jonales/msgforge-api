-- V202611151142__cria_bot_com_ia_por_instancia
-- Motivo: bot com IA por instância (ADR 0015). instance_bot guarda a configuração (uma por instância);
-- bot_session, o estado do bot em cada conversa. O que o bot envia entra no histórico com sent_by BOT.
-- Lembretes: não edite depois do merge; atualize a entidade JPA no mesmo commit;
-- dê nome às constraints (pk_/fk_/uk_/ix_/ck_). Ver docs/MIGRATIONS.md.

CREATE TABLE instance_bot (
    instance_id       UUID          NOT NULL,
    enabled           BOOLEAN       NOT NULL DEFAULT FALSE,
    provider          VARCHAR(20)   NOT NULL DEFAULT 'OPENAI',
    base_url          VARCHAR(500)  NOT NULL,
    model             VARCHAR(120)  NOT NULL,
    api_key_encrypted VARCHAR(2000),
    system_prompt     TEXT          NOT NULL DEFAULT '',
    trigger_mode      VARCHAR(10)   NOT NULL DEFAULT 'ALL',
    keywords          VARCHAR(1000) NOT NULL DEFAULT '',
    stop_keyword      VARCHAR(60)   NOT NULL DEFAULT '',
    session_minutes   INTEGER       NOT NULL DEFAULT 0,
    ignore_groups     BOOLEAN       NOT NULL DEFAULT TRUE,
    pause_on_human    BOOLEAN       NOT NULL DEFAULT TRUE,
    resume_mode       VARCHAR(10)   NOT NULL DEFAULT 'AUTOMATIC',
    pause_minutes     INTEGER       NOT NULL DEFAULT 30,
    context_messages  INTEGER       NOT NULL DEFAULT 10,
    typing_seconds    INTEGER       NOT NULL DEFAULT 0,
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_instance_bot PRIMARY KEY (instance_id),
    CONSTRAINT fk_instance_bot_instance FOREIGN KEY (instance_id) REFERENCES instance (id) ON DELETE CASCADE,
    CONSTRAINT ck_instance_bot_provider CHECK (provider IN ('OPENAI')),
    CONSTRAINT ck_instance_bot_trigger CHECK (trigger_mode IN ('ALL', 'KEYWORD', 'EXACT')),
    CONSTRAINT ck_instance_bot_resume CHECK (resume_mode IN ('AUTOMATIC', 'MANUAL')),
    CONSTRAINT ck_instance_bot_numbers CHECK (session_minutes >= 0 AND pause_minutes >= 1
        AND context_messages BETWEEN 1 AND 50 AND typing_seconds BETWEEN 0 AND 10)
);

CREATE TABLE bot_session (
    instance_id      UUID         NOT NULL,
    remote_jid       VARCHAR(128) NOT NULL,
    status           VARCHAR(8)   NOT NULL,
    paused_until     TIMESTAMPTZ,
    last_activity_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_bot_session PRIMARY KEY (instance_id, remote_jid),
    CONSTRAINT fk_bot_session_instance FOREIGN KEY (instance_id) REFERENCES instance (id) ON DELETE CASCADE,
    CONSTRAINT ck_bot_session_status CHECK (status IN ('ACTIVE', 'PAUSED', 'CLOSED'))
);

ALTER TABLE message DROP CONSTRAINT ck_message_sent_by;
ALTER TABLE message ADD CONSTRAINT ck_message_sent_by
    CHECK (sent_by IN ('USER', 'MANAGER_KEY', 'API_KEY', 'API_TOKEN', 'BOT'));
