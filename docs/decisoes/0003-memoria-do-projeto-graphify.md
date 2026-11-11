# 0003: Memória do projeto: CLAUDE.md, ADRs e Graphify

**Status:** aceita (2026-11)

## Contexto
As sessões do Claude Code em nuvem começam do zero, num container novo. Sem uma memória
versionada, cada sessão redescobre a arquitetura, as convenções e os bugs conhecidos, o que
gasta tempo e tokens e repete erros.

## Decisão
Três camadas, da mais estável para a mais volátil:
1. **`CLAUDE.md`:** arquitetura, convenções, comandos e backlog de problemas conhecidos.
2. **`docs/decisoes/`:** ADRs com o porquê das escolhas grandes.
3. **Graphify** (`graphifyy[sql]`, versão fixada em `.claude/hooks/session-start.sh`):
   - O grafo de código (`graphify update .`, via AST, sem LLM) é **gerado** a cada sessão e
     **não** é versionado: ele é reescrito por inteiro a cada build e geraria diffs enormes e
     conflitos.
   - As memórias (`graphify-out/memory/`) e as lições consolidadas
     (`graphify-out/reflections/LESSONS.md`) **são** versionadas.
   - A skill `/graphify` fica vendorizada em `.claude/skills/graphify/`. Os hooks de
     PreToolUse passam por `.claude/hooks/graphify-guard.sh`, que não faz nada se o Graphify
     não estiver instalado.

## Consequências
- Uma sessão nova já sabe o que existe, o que está quebrado e por quê.
- O grafo precisa ser reconstruído no início da sessão (rápido, com cache de AST).
- A skill vendorizada precisa ser atualizada junto com a versão fixada do `graphifyy`
  (`graphify install --platform claude` e copiar para `.claude/skills/`).
