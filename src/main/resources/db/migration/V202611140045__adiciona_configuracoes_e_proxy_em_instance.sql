-- V202611140045__adiciona_configuracoes_e_proxy_em_instance
-- Motivo: o POST /instance/create passa a guardar as configurações do contrato (rejectCall, msgCall,
-- groupsIgnore, alwaysOnline, readMessages, readStatus) e o proxy da instância, em vez de
-- descartá-los. Padrões = os do contrato (tudo desligado, sem proxy).
-- A senha do proxy fica cifrada (AES-GCM, chave SECRETS_ENCRYPTION_KEY; ADR 0014), nunca em texto.

ALTER TABLE instance ADD COLUMN reject_call    BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE instance ADD COLUMN msg_call       VARCHAR(4096);
ALTER TABLE instance ADD COLUMN groups_ignore  BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE instance ADD COLUMN always_online  BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE instance ADD COLUMN read_messages  BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE instance ADD COLUMN read_status    BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE instance ADD COLUMN proxy_protocol           VARCHAR(10);
ALTER TABLE instance ADD COLUMN proxy_host               VARCHAR(253);
ALTER TABLE instance ADD COLUMN proxy_port               INTEGER;
ALTER TABLE instance ADD COLUMN proxy_username           VARCHAR(255);
ALTER TABLE instance ADD COLUMN proxy_password_encrypted VARCHAR(1024);

ALTER TABLE instance ADD CONSTRAINT ck_instance_proxy_protocol
    CHECK (proxy_protocol IN ('http', 'https', 'socks4', 'socks4a', 'socks5', 'socks5h'));
ALTER TABLE instance ADD CONSTRAINT ck_instance_proxy_port
    CHECK (proxy_port BETWEEN 1 AND 65535);
-- Proxy é tudo ou nada: protocolo, host e porta juntos
ALTER TABLE instance ADD CONSTRAINT ck_instance_proxy_completo
    CHECK ((proxy_protocol IS NULL AND proxy_host IS NULL AND proxy_port IS NULL
            AND proxy_username IS NULL AND proxy_password_encrypted IS NULL)
        OR (proxy_protocol IS NOT NULL AND proxy_host IS NOT NULL AND proxy_port IS NOT NULL));
