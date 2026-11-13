# Plantão local (LLM no Ollama)

Um agente com LLM local que trabalha **junto** com os agentes do Claude e **assume** os passos
marcados como locais quando o Claude bate no limite de uso, para o projeto não parar.

```bash
nohup python3 -I scripts/agente-local/plantao.py > .agente-local/nohup.out 2>&1 &   # sobe
pkill -f agente-local/plantao.py                                                    # para
cat .agente-local/estado.json; tail .agente-local/plantao.log                       # como está
```

Requer o Ollama em `localhost:11434`. Modelos por variável: `PLANTAO_MODELO_TEXTO` e
`PLANTAO_MODELO_CODIGO` (padrão `qwen2.5:7b`; para código, um modelo de código maior, como o
`qwen2.5-coder:14b`, rende bem mais).

## Modos
- **Conjunto** (normal): processa a fila em `.agente-local/fila/` (resumos, testes, revisão
  prévia de diff), liberando o Claude do trabalho braçal.
- **Limite:** ligado quando o último erro `rate_limit` nos transcritos do Claude Code deste
  projeto é mais novo que a última resposta do Claude e ainda não passou o horário de volta.
  Nesse modo ele também colhe os passos `[local:...]` dos `HANDOFF.md` dos worktrees. Para ligar à
  mão: `touch .agente-local/forcar`.

## Tarefas (`.agente-local/fila/<id>.json`)
| tipo | campos | o que faz |
|---|---|---|
| `resumo` | `pergunta`, `arquivos` | responde sobre os arquivos |
| `testes` | `pasta` (worktree), `comando` (`mvn-test`, `mvn-test-classe` + `params.classe`, `mvn-compile`) | roda e analisa as falhas |
| `revisao` | `pasta`, `base` | revisão prévia do `git diff base...HEAD` contra o `CLAUDE.md` |
| `patch` | `pasta`, `instrucao`, `arquivos`, `verificacao` | aplica blocos busca/troca num worktree novo `local/<id>`; commit local só se a verificação passar |

`"so_no_limite": true` faz a tarefa esperar o modo limite. Resultados em `.agente-local/saida/`.

## Passagem (HANDOFF)
Cada agente do Claude mantém `<worktree>/.agente-local/HANDOFF.md`:

```markdown
## Estado
O que está pronto e testado, branch, último commit.
## Próximos passos
- [ ] [local:testes] rodar os testes unitários
- [ ] [local:patch] alteração pequena e mecânica em `src/main/java/.../X.java`
- [ ] passo que só o Claude faz (sem marca)
```

## Limites e segurança
- Nunca faz push, merge nem mexe no checkout principal; comandos só da lista `COMANDOS`, sem
  shell; rede só para o Ollama local.
- O que ele produz é **rascunho**: o Claude (ou você) revisa antes de aproveitar.
- Um modelo de 7B não substitui o Claude em código de arquitetura. Ele mantém o andamento em
  testes, revisão prévia, resumos e ajustes pequenos.
