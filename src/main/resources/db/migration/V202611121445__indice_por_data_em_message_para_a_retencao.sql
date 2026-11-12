-- V202611121445__indice_por_data_em_message_para_a_retencao
-- Motivo: indice por data em message para a retencao
-- Lembretes: não edite depois do merge; atualize a entidade JPA no mesmo commit;
-- dê nome às constraints (pk_/fk_/uk_/ix_/ck_). Ver docs/MIGRATIONS.md.

-- A limpeza da retenção (HistoryRetention) apaga por message_timestamp em toda a tabela; sem este
-- índice, cada lote varreria a tabela inteira. A tabela é nova, então o CREATE INDEX comum basta.
CREATE INDEX ix_message_ts ON message (message_timestamp);
