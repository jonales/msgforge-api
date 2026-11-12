# Desenvolvimento

```bash
mvn test                              # testes unitários
mvn verify                            # + integração (PostgreSQL e Redis via Testcontainers)
node scripts/e2e/manager.js           # E2E do Manager (com a aplicação rodando no modo stub)
scripts/new-migration.sh "descricao"  # nova migration Flyway no padrão do projeto
```

- **Banco:** só por migration, no padrão de [MIGRATIONS.md](MIGRATIONS.md).
- **CI:** GitHub Actions (`mvn verify` + build da imagem Docker).
- **Memória para agentes (Claude Code):** [CLAUDE.md](../CLAUDE.md), skills em `.claude/skills/` e
  [Graphify](https://github.com/Graphify-Labs/graphify) (`pip install "graphifyy[sql]==0.9.106"`,
  depois `graphify update .`). As memórias versionadas ficam em `graphify-out/memory/`.
