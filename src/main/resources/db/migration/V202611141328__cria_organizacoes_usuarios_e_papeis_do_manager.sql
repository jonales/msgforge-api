-- V202611141328__cria_organizacoes_usuarios_e_papeis_do_manager
-- Motivo: acesso ao Manager por organização, usuário e papel (ADR 0010, fatias 1 e 2).
-- O catálogo de permissões é fixo no código (enum Permission); os papéis são dados de cada
-- organização. A organização padrão recebe os papéis-modelo (RoleTemplate) e, na migration
-- seguinte, as instâncias que já existem.
-- Lembretes: não edite depois do merge; atualize a entidade JPA no mesmo commit;
-- dê nome às constraints (pk_/fk_/uk_/ix_/ck_). Ver docs/MIGRATIONS.md.

CREATE TABLE organization (
    id         UUID         NOT NULL,
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_organization PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_organization_name ON organization (lower(name));

-- Papel: um nome e um conjunto de permissões do catálogo, definido pela organização
CREATE TABLE role (
    id              UUID        NOT NULL,
    organization_id UUID        NOT NULL,
    name            VARCHAR(60) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_role PRIMARY KEY (id),
    CONSTRAINT fk_role_organization FOREIGN KEY (organization_id)
        REFERENCES organization (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uk_role_organization_name ON role (organization_id, lower(name));

-- Uma permissão nova no catálogo exige uma migration que a acrescente a este CHECK (ADR 0010)
CREATE TABLE role_permission (
    role_id    UUID        NOT NULL,
    permission VARCHAR(40) NOT NULL,
    CONSTRAINT pk_role_permission PRIMARY KEY (role_id, permission),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES role (id) ON DELETE CASCADE,
    CONSTRAINT ck_role_permission_permission CHECK (permission IN (
        'INSTANCE_VIEW', 'INSTANCE_CONNECT', 'INSTANCE_CONNECT_LINK', 'INSTANCE_CREATE', 'INSTANCE_DELETE',
        'CHAT_VIEW', 'CHAT_SEND',
        'EVENTS_CONFIGURE', 'ACCOUNT_CONFIGURE',
        'USERS_MANAGE', 'ROLES_MANAGE', 'AUDIT_VIEW'))
);

-- Usuário do Manager. E-mail guardado em minúsculas (único sem diferenciar maiúsculas); a senha só
-- como hash do DelegatingPasswordEncoder ("{bcrypt}..."). Convidado ainda não tem senha.
CREATE TABLE app_user (
    id             UUID         NOT NULL,
    email          VARCHAR(254) NOT NULL,
    password_hash  VARCHAR(255),
    status         VARCHAR(12)  NOT NULL DEFAULT 'ATIVO',
    platform_admin BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at  TIMESTAMPTZ,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uk_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_email_minusculo CHECK (email = lower(email)),
    CONSTRAINT ck_app_user_status CHECK (status IN ('CONVIDADO', 'ATIVO', 'BLOQUEADO')),
    CONSTRAINT ck_app_user_senha_se_ativo CHECK (status = 'CONVIDADO' OR password_hash IS NOT NULL)
);

-- Vínculo de um usuário com uma organização, com um papel. all_instances = false limita o vínculo
-- às instâncias de membership_instance (escopo opcional por instância).
-- Papel em uso não pode ser apagado: ON DELETE NO ACTION (checado no fim do comando, para o
-- CASCADE de uma organização inteira apagar vínculos e papéis juntos).
CREATE TABLE membership (
    id              UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    organization_id UUID        NOT NULL,
    role_id         UUID        NOT NULL,
    all_instances   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_membership PRIMARY KEY (id),
    CONSTRAINT uk_membership_user_organization UNIQUE (user_id, organization_id),
    CONSTRAINT fk_membership_app_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_membership_organization FOREIGN KEY (organization_id)
        REFERENCES organization (id) ON DELETE CASCADE,
    CONSTRAINT fk_membership_role FOREIGN KEY (role_id) REFERENCES role (id) ON DELETE NO ACTION
);
CREATE INDEX ix_membership_organization_id ON membership (organization_id);
CREATE INDEX ix_membership_role_id ON membership (role_id);

CREATE TABLE membership_instance (
    membership_id UUID NOT NULL,
    instance_id   UUID NOT NULL,
    CONSTRAINT pk_membership_instance PRIMARY KEY (membership_id, instance_id),
    CONSTRAINT fk_membership_instance_membership FOREIGN KEY (membership_id)
        REFERENCES membership (id) ON DELETE CASCADE,
    CONSTRAINT fk_membership_instance_instance FOREIGN KEY (instance_id)
        REFERENCES instance (id) ON DELETE CASCADE
);
CREATE INDEX ix_membership_instance_instance_id ON membership_instance (instance_id);

-- Organização padrão (id fixo, Organization.DEFAULT_ID) com os papéis-modelo (RoleTemplate)
INSERT INTO organization (id, name) VALUES ('00000000-0000-0000-0000-000000000001', 'Padrão');

INSERT INTO role (id, organization_id, name) VALUES
    ('00000000-0000-0000-0001-000000000001', '00000000-0000-0000-0000-000000000001', 'Gestor'),
    ('00000000-0000-0000-0001-000000000002', '00000000-0000-0000-0000-000000000001', 'Operador'),
    ('00000000-0000-0000-0001-000000000003', '00000000-0000-0000-0000-000000000001', 'Atendente'),
    ('00000000-0000-0000-0001-000000000004', '00000000-0000-0000-0000-000000000001', 'Cliente');

INSERT INTO role_permission (role_id, permission)
SELECT '00000000-0000-0000-0001-000000000001', p FROM unnest(ARRAY[
    'INSTANCE_VIEW', 'INSTANCE_CONNECT', 'INSTANCE_CONNECT_LINK', 'INSTANCE_CREATE', 'INSTANCE_DELETE',
    'CHAT_VIEW', 'CHAT_SEND', 'EVENTS_CONFIGURE', 'ACCOUNT_CONFIGURE',
    'USERS_MANAGE', 'ROLES_MANAGE', 'AUDIT_VIEW']) AS p;
INSERT INTO role_permission (role_id, permission)
SELECT '00000000-0000-0000-0001-000000000002', p FROM unnest(ARRAY[
    'INSTANCE_VIEW', 'INSTANCE_CONNECT', 'INSTANCE_CONNECT_LINK', 'CHAT_VIEW', 'CHAT_SEND']) AS p;
INSERT INTO role_permission (role_id, permission)
SELECT '00000000-0000-0000-0001-000000000003', p FROM unnest(ARRAY[
    'INSTANCE_VIEW', 'CHAT_VIEW', 'CHAT_SEND']) AS p;
INSERT INTO role_permission (role_id, permission)
SELECT '00000000-0000-0000-0001-000000000004', p FROM unnest(ARRAY[
    'INSTANCE_VIEW', 'INSTANCE_CONNECT']) AS p;
