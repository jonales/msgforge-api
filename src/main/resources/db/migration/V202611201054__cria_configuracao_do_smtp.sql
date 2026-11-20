-- V202611201054__cria_configuracao_do_smtp
-- Motivo: SMTP configurado pelo Manager (ADR 0019): uma linha só, que vence o SMTP_* do ambiente.
-- Lembretes: não edite depois do merge; atualize a entidade JPA no mesmo commit;
-- dê nome às constraints (pk_/fk_/uk_/ix_/ck_). Ver docs/MIGRATIONS.md.
--
-- Gravada por SQL direto (notification/SmtpSettingsStore), sem entidade JPA. A senha vai cifrada pelo
-- SecretCipher (ADR 0014); nunca em claro.

CREATE TABLE smtp_settings (
    id                 SMALLINT     NOT NULL DEFAULT 1,
    host               VARCHAR(253) NOT NULL,
    port               INTEGER      NOT NULL,
    username           VARCHAR(254),
    password_encrypted TEXT,
    from_address       VARCHAR(320),
    starttls           BOOLEAN      NOT NULL DEFAULT TRUE,
    timeout_ms         INTEGER      NOT NULL DEFAULT 10000,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by         VARCHAR(254) NOT NULL,
    CONSTRAINT pk_smtp_settings PRIMARY KEY (id),
    CONSTRAINT ck_smtp_settings_uma_linha CHECK (id = 1),
    CONSTRAINT ck_smtp_settings_porta CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT ck_smtp_settings_timeout CHECK (timeout_ms BETWEEN 1000 AND 120000)
);

-- Auditoria (ADR 0018): mudança e remoção da configuração do SMTP
ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_action;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_action CHECK (action IN (
    'LOGIN', 'LOGOUT',
    'USER_CREATED', 'USER_ROLE_CHANGED', 'USER_BLOCKED', 'USER_UNBLOCKED', 'USER_RENAMED',
    'ROLE_CREATED', 'ROLE_UPDATED', 'ROLE_DELETED',
    'SERVER_SMTP_UPDATED', 'SERVER_SMTP_REMOVED'));
