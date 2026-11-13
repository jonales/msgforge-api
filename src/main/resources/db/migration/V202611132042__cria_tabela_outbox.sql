-- V202611132042__cria_tabela_outbox
-- Motivo: cria tabela outbox
-- Lembretes: não edite depois do merge; atualize a entidade JPA no mesmo commit;
-- dê nome às constraints (pk_/fk_/uk_/ix_/ck_). Ver docs/MIGRATIONS.md.

-- Outbox das entregas de eventos (ADR 0012): uma linha por evento e destino, gravada antes da
-- entrega. Sem FK para instance: a entrega usa o snapshot (ADR 0004) e sobrevive ao delete.
-- O id é sequencial porque define a ordem de entrega dentro de cada chave (ADR 0006).
-- payload é JSON (e não JSONB) de propósito: o JSON guarda o texto exato, então o corpo que
-- sai para o webhook e o RabbitMQ é byte a byte o mesmo do caminho em memória (o JSONB reordena
-- as chaves dos objetos).

CREATE TABLE outbox (
    id                    BIGINT       GENERATED ALWAYS AS IDENTITY,
    type                  VARCHAR(20)  NOT NULL,
    order_key             VARCHAR(255) NOT NULL,
    event                 VARCHAR(64)  NOT NULL,
    destination           TEXT         NOT NULL,
    headers               JSONB        NOT NULL DEFAULT '{}',
    check_private_network BOOLEAN      NOT NULL DEFAULT false,
    payload               JSON         NOT NULL,
    state                 VARCHAR(10)  NOT NULL DEFAULT 'PENDENTE',
    attempts              INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error            TEXT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_outbox PRIMARY KEY (id),
    -- Um tipo novo (EMAIL, ADR 0011) entra trocando esta constraint numa migration nova
    CONSTRAINT ck_outbox_type CHECK (type IN ('WEBHOOK', 'WEBHOOK_GLOBAL', 'RABBITMQ')),
    CONSTRAINT ck_outbox_state CHECK (state IN ('PENDENTE', 'EM_ENVIO', 'FEITO', 'MORTO'))
);

-- Fila viva de cada chave (tipo + instância), na ordem do id: acha a cabeça de cada fila.
-- Parcial: FEITO e MORTO, a maior parte da tabela, ficam fora do índice.
CREATE INDEX ix_outbox_order_key_state ON outbox (type, order_key, id)
    WHERE state IN ('PENDENTE', 'EM_ENVIO');

-- O que já pode ser tentado (varredura do trabalhador) e os EM_ENVIO abandonados.
CREATE INDEX ix_outbox_next_attempt_at ON outbox (next_attempt_at)
    WHERE state IN ('PENDENTE', 'EM_ENVIO');

-- Retenção: apaga os FEITO mais velhos em lotes.
CREATE INDEX ix_outbox_done_updated_at ON outbox (updated_at) WHERE state = 'FEITO';
