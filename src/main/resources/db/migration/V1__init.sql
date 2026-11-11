CREATE TABLE instance (
    id               UUID PRIMARY KEY,
    name             VARCHAR(100) NOT NULL UNIQUE,
    token            VARCHAR(255) NOT NULL,
    integration      VARCHAR(40)  NOT NULL DEFAULT 'WHATSAPP-BAILEYS',
    number           VARCHAR(30),
    owner_jid        VARCHAR(100),
    profile_name     VARCHAR(255),
    connection_state VARCHAR(20)  NOT NULL DEFAULT 'close',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE webhook (
    id          UUID PRIMARY KEY,
    instance_id UUID         NOT NULL UNIQUE REFERENCES instance (id) ON DELETE CASCADE,
    url         VARCHAR(500) NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    by_events   BOOLEAN      NOT NULL DEFAULT FALSE,
    base64      BOOLEAN      NOT NULL DEFAULT FALSE,
    events      TEXT         NOT NULL DEFAULT '',
    headers     TEXT
);

-- Sessão do WhatsApp (chaves Signal/credenciais), gravada pelo gateway
CREATE TABLE session_store (
    instance_id UUID PRIMARY KEY REFERENCES instance (id) ON DELETE CASCADE,
    payload     BYTEA       NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
