# Padrão de migrations (Flyway)

O schema do banco é controlado **só** por migrations Flyway em
`src/main/resources/db/migration/`. O Hibernate roda com `ddl-auto: validate`: ele confere
se as entidades batem com o schema, mas nunca altera o banco.

## Nome do arquivo

```
V{yyyyMMddHHmm}__{descricao_em_snake_case}.sql     versionada (padrão)
R__{descricao_em_snake_case}.sql                   repetível (views, functions)
```

Exemplos: `V202611111430__cria_tabela_message.sql`, `R__view_instance_stats.sql`.

- A versão é a **data e hora UTC** de criação, e não um número sequencial. Assim, duas branches
  (ou duas sessões do Claude em paralelo) não disputam o mesmo `V2`.
- `V1__init.sql` é a linha de base histórica e fica como está.
- A descrição usa só `a-z`, `0-9` e `_`, começa com verbo e é curta: `cria_`, `adiciona_`,
  `remove_`, `renomeia_`, `indexa_`, `popula_`.
- O teste `MigrationConventionTest` e o `spring.flyway.validate-migration-naming` reprovam
  nomes fora do padrão.

Para criar uma migration, use o script, que gera o nome e o cabeçalho:

```bash
scripts/new-migration.sh "adiciona coluna profile_picture em instance"
```

## Regras

1. **Nunca edite uma migration que já foi para `main`.** O Flyway guarda o checksum e a
   aplicação não sobe se o arquivo mudar. Para corrigir, crie uma migration nova.
2. **Uma mudança lógica por arquivo.** Isso facilita o review e o rollback manual.
3. **Migration e entidade JPA vão no mesmo commit.** Com `ddl-auto: validate`, uma sem a
   outra derruba o boot.
4. **Sem `DROP` destrutivo direto.** Para remover uma coluna em uso, faça em duas versões:
   primeiro o código para de usar a coluna, depois uma migration posterior remove.
5. **Mudanças compatíveis para trás.** A versão N-1 da aplicação precisa continuar
   funcionando com o schema N, para permitir rolling deploy.
6. **Tabelas grandes:** `CREATE INDEX CONCURRENTLY` exige uma migration separada com o
   cabeçalho `-- flyway:executeInTransaction=false`.
7. **Dados de exemplo e de teste não vão em migration.** Use os recursos de teste.

## Convenções de schema (PostgreSQL)

| Item | Padrão |
|---|---|
| Tabela | singular, snake_case: `instance`, `chat_message` |
| PK | `id UUID PRIMARY KEY`, gerado na aplicação (`UUID.randomUUID()`) |
| FK | `{tabela}_id`, sempre com `ON DELETE` explícito e um índice |
| Datas | `TIMESTAMPTZ`; `created_at` e `updated_at` com `DEFAULT now()` |
| Texto | `VARCHAR(n)` quando há limite de negócio; `TEXT` nos demais casos |
| JSON | `JSONB` |
| Booleano | `BOOLEAN NOT NULL DEFAULT ...` |
| Nomes de constraint | `pk_{tabela}`, `fk_{tabela}_{ref}`, `uk_{tabela}_{colunas}`, `ix_{tabela}_{colunas}`, `ck_{tabela}_{regra}` |
| Enum | `VARCHAR` com `CHECK`, nunca `CREATE TYPE ... ENUM` (difícil de alterar) |

As constraints sem nome da `V1__init.sql` ficam como estão (regra 1).

## Checklist do PR

- [ ] Nome no padrão (gerado por `scripts/new-migration.sh`)
- [ ] Entidade JPA atualizada no mesmo commit
- [ ] Nenhuma migration antiga alterada
- [ ] Constraints e índices com nome
- [ ] `mvn test` passando
