# Plantão local (LLM no Ollama)

Agente com LLM local que trabalha **junto** com os agentes do Claude: refina pedidos antes de
chegarem ao Claude, roda testes, faz revisão prévia e ajustes pequenos, e **assume** os passos
marcados como locais quando o Claude bate no limite de uso.

```bash
nohup python3 -I scripts/agente-local/plantao.py >> .agente-local/nohup.out 2>&1 &   # sobe
pkill -f agente-local/plantao.py                                                     # para
```

Modelos (variáveis `PLANTAO_MODELO_TEXTO` e `PLANTAO_MODELO_CODIGO`): `qwen2.5:7b` para resumos;
`qwen2.5-coder:14b`, se instalado, para refinar, perguntar, revisar e alterar código.

## Passar tarefas (`tarefa.py`)

```bash
T=scripts/agente-local/tarefa.py
python3 -I $T refinar "pedido cru"                  # especificação pronta para o Claude
python3 -I $T pergunta "como o X decide Y?"         # responde a partir do código das classes citadas
python3 -I $T resumo "o que resumir" -a docs/x.md
python3 -I $T revisao --ref minha-branch            # revisão prévia do diff contra origin/main
python3 -I $T testes [--ref main] [--classe XTest | --it XIT]
python3 -I $T patch "alteração pequena" -a src/.../X.java [--verificacao mvn-test-classe --classe XTest]
python3 -I $T status | ver [id] | licoes
python3 -I $T nota <id> "o que ele errou e como deve ser"   # ensina (vai para .agente-local/licoes.md)
```

Saídas em `.agente-local/saida/<id>.md`. Cada saída é **rascunho**: o Claude (ou você) revisa.

## Como ele aprende
Não há treino do modelo. O aprendizado são as **lições** (`.agente-local/licoes.md`), que entram
em todas as tarefas como regras, e o **contexto** que o plantão monta: o grafo do código
(`graphify query`, atualizado sozinho quando fica mais velho que o `main`) e o fonte das classes
citadas pelo nome. Toda saída termina com a conferência dos caminhos citados (✓ existe, ✗ não).

## Gatilho de 90% do plano
A cada passada, o plantão estima o consumo do plano do Claude na janela de 5 horas
(`consumo.py`, a partir dos tokens registrados nos transcritos e calibrado pelos estouros de
limite já vistos; erro de ~20%). Ao chegar em `PLANTAO_LIMIAR_PLANO` (0.9), gera uma vez por
janela a fila de pendências de `PLANTAO_HORAS_DA_FILA` horas (`fila_pendencias.py`): passos
`[local:...]` dos HANDOFF, suíte e revisão das branches fora do main, refinamento do
`.agente-local/backlog.md`, auditoria dos arquivos alterados e, para completar as horas, rodadas da
suíte completa do main. `tarefa.py consumo` mostra a estimativa; `tarefa.py fila [horas]` gera já.

## Modo limite e passagem (HANDOFF)
Quando o último erro `rate_limit` nos transcritos do Claude Code deste projeto é mais novo que a
última resposta do Claude e ainda não passou o horário de volta, o plantão também executa os
passos `[local:...]` dos `HANDOFF.md` dos worktrees. Para forçar: `touch .agente-local/forcar`.

```markdown
## Estado
O que está pronto e testado, branch, último commit.
## Próximos passos
- [ ] [local:testes] rodar os testes unitários
- [ ] [local:patch] alteração pequena e mecânica em `src/main/java/.../X.java`
- [ ] passo que só o Claude faz (sem marca)
```

## Segurança e limites
- Rede só para o Ollama local. Nunca faz push, merge nem mexe no checkout principal: testes e
  patches rodam em worktrees próprios (`.claude/worktrees/local-<id>`, branch `local/<id>` só
  quando há commit). Comandos só da lista `COMANDOS`, sem shell.
- Se o runner do Ollama travar em "Stopping...", o plantão encerra só o runner e tenta de novo.
- Tarefa que falha volta uma vez para a fila; na segunda, vai para `falhou/`.
- Um modelo local não substitui o Claude em decisões de arquitetura: ele prepara, confere e
  executa o mecânico.
