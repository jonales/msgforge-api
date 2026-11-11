-- V202611111355__adiciona_uk_instance_token
-- Motivo: adiciona uk instance token
-- Lembretes: não edite depois do merge; atualize a entidade JPA no mesmo commit;
-- dê nome às constraints (pk_/fk_/uk_/ix_/ck_). Ver docs/MIGRATIONS.md.

-- O token da instância autentica requisições (header apikey) e identifica a instância em
-- /instance/fetchInstances; dois tokens iguais tornariam essa resolução ambígua.
ALTER TABLE instance ADD CONSTRAINT uk_instance_token UNIQUE (token);
