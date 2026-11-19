-- V202611192229__cria_tabela_de_auditoria
-- Motivo: auditoria do Manager (ADR 0010, "auditoria mínima"; ADR 0018): login e ações de pessoas.
-- Lembretes: não edite depois do merge; atualize a entidade JPA no mesmo commit;
-- dê nome às constraints (pk_/fk_/uk_/ix_/ck_). Ver docs/MIGRATIONS.md.
--
-- Sem chave estrangeira para usuário e organização, de propósito: o registro sobrevive a quem foi
-- apagado depois, e o rótulo de quem agiu (actor_label) fica como era na hora. Gravada por SQL direto
-- (audit/AuditLog), sem entidade JPA, como o histórico de mensagens (ADR 0008).

CREATE TABLE audit_event (
    id              BIGSERIAL    NOT NULL,
    at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    action          VARCHAR(30)  NOT NULL,
    outcome         VARCHAR(12)  NOT NULL,
    organization_id UUID,
    actor_user_id   UUID,
    actor_label     VARCHAR(254) NOT NULL,
    target          VARCHAR(254),
    detail          VARCHAR(200),
    ip              VARCHAR(45),
    CONSTRAINT pk_audit_event PRIMARY KEY (id),
    CONSTRAINT ck_audit_event_action CHECK (action IN (
        'LOGIN', 'LOGOUT',
        'USER_CREATED', 'USER_ROLE_CHANGED', 'USER_BLOCKED', 'USER_UNBLOCKED', 'USER_RENAMED',
        'ROLE_CREATED', 'ROLE_UPDATED', 'ROLE_DELETED')),
    CONSTRAINT ck_audit_event_outcome CHECK (outcome IN ('OK', 'FAILED', 'BLOCKED', 'DISABLED'))
);

-- A tela lista do mais novo para o mais antigo, por organização; a retenção apaga pelo tempo.
CREATE INDEX ix_audit_event_at ON audit_event (at);
CREATE INDEX ix_audit_event_org_at ON audit_event (organization_id, at DESC);
CREATE INDEX ix_audit_event_actor_at ON audit_event (actor_user_id, at DESC);
