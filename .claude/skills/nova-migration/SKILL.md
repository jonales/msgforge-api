---
name: nova-migration
description: Cria ou revisa uma migration Flyway do Msgforge no padrão do projeto. Use sempre que precisar mudar o schema do banco (tabela, coluna, índice, constraint, dados de referência) ou alterar uma entidade JPA.
---

# Nova migration Flyway

O padrão completo está em `docs/MIGRATIONS.md`; leia-o se tiver dúvida. O resumo:

1. **Gere o arquivo pelo script**, nunca à mão:
   ```bash
   scripts/new-migration.sh "adiciona coluna profile_picture em instance"
   # view, function ou trigger reaplicável:
   scripts/new-migration.sh --repeatable "view instance stats"
   ```
2. **Escreva o SQL** (PostgreSQL), com uma mudança lógica por arquivo:
   - Dê nome a toda constraint e índice: `pk_`, `fk_{tabela}_{ref}`, `uk_`, `ix_{tabela}_{cols}`, `ck_`.
   - FK sempre com `ON DELETE` explícito e um índice próprio.
   - Datas em `TIMESTAMPTZ`, JSON em `JSONB`, enum como `VARCHAR` + `CHECK`.
   - Coluna nova `NOT NULL` em tabela existente precisa de `DEFAULT` (ou ser feita em duas etapas).
   - `CREATE INDEX CONCURRENTLY` vai em arquivo próprio com `-- flyway:executeInTransaction=false`.
3. **Atualize a entidade JPA no mesmo commit.** Com `ddl-auto: validate`, uma sem a outra derruba o boot.
4. **Nunca edite** uma migration que já está em `main` (`git log origin/main -- <arquivo>`).
   Para corrigir, crie outra.
5. **Valide:** `mvn test` roda o `MigrationConventionTest`. Se houver Postgres disponível,
   suba a aplicação e confira no log `Successfully applied`.
6. Depois de mudar código, rode `graphify update .`.

Remover coluna ou tabela em uso: primeiro um PR que tira o uso no código; depois, em outro
PR, a migration com o `DROP`.
