-- V202611151416__adiciona_modelo_da_assinatura
-- Motivo: a assinatura do atendente segue um modelo por instância, com {nome} no lugar do nome de
-- exibição (padrão "Atendente {nome}": o cliente sabe que é uma pessoa respondendo). Pedido no teste ao
-- vivo de 2026-11-15.
-- Lembretes: não edite depois do merge; atualize a entidade JPA no mesmo commit;
-- dê nome às constraints (pk_/fk_/uk_/ix_/ck_). Ver docs/MIGRATIONS.md.

ALTER TABLE instance ADD COLUMN signature_template VARCHAR(60) NOT NULL DEFAULT 'Atendente {nome}';
