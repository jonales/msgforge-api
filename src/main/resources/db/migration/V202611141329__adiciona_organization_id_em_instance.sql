-- V202611141329__adiciona_organization_id_em_instance
-- Motivo: cada instância pertence a uma organização (ADR 0010). As que já existem vão para a
-- organização padrão, pelo DEFAULT; a API (que não conhece organizações) continua criando nela.
-- Organização com instâncias não pode ser apagada: ON DELETE RESTRICT.
-- Lembretes: não edite depois do merge; atualize a entidade JPA no mesmo commit;
-- dê nome às constraints (pk_/fk_/uk_/ix_/ck_). Ver docs/MIGRATIONS.md.

ALTER TABLE instance ADD COLUMN organization_id UUID NOT NULL
    DEFAULT '00000000-0000-0000-0000-000000000001';
ALTER TABLE instance ADD CONSTRAINT fk_instance_organization FOREIGN KEY (organization_id)
    REFERENCES organization (id) ON DELETE RESTRICT;
CREATE INDEX ix_instance_organization_id ON instance (organization_id);
