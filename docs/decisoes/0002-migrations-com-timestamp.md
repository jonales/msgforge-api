# 0002: Migrations Flyway versionadas por timestamp

**Status:** aceita (2026-11)

## Contexto
Várias branches, e várias sessões do Claude em paralelo, vão criar migrations ao mesmo tempo.
Com versões sequenciais (`V2`, `V3`), duas branches criam `V2` e uma delas quebra no merge.

## Decisão
- Novo padrão `V{yyyyMMddHHmm}__descricao_snake_case.sql` (UTC), gerado por
  `scripts/new-migration.sh`. `V1__init.sql` fica como linha de base.
- O padrão é verificado em dois lugares: `MigrationConventionTest` no build e
  `spring.flyway.validate-migration-naming` no boot.
- `out-of-order: false`: uma migration com timestamp mais antigo que a última aplicada falha.
  Quem fizer o merge regenera o nome com o script.
- Regras completas em `docs/MIGRATIONS.md`.

## Consequências
- Colisões de versão entre branches ficam praticamente impossíveis.
- Os nomes são menos legíveis que `V2`, `V3`; a ordem continua cronológica.
