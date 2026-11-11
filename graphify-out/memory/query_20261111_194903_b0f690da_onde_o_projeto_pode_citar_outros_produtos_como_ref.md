---
type: "query"
date: "2026-11-11T19:49:03.298125+00:00"
question: "Onde o projeto pode citar outros produtos como referencia?"
contributor: "graphify"
outcome: "corrected"
correction: "A regra vale para o repositorio inteiro, nao so para o README."
---

# Q: Onde o projeto pode citar outros produtos como referencia?

## Answer

So em docs/COMPATIBILIDADE.md (que documenta de onde vem o contrato publico). Decisao do usuario (2026-11), que amplia a regra do README: README, codigo, comentarios, configuracao (.env.example, application.yml, pom.xml), ADRs, skills e CLAUDE.md falam do 'contrato da API' / 'contrato publico' do Msgforge. O ADR 0001 foi renomeado para 0001-contrato-e-gateway.md. As memorias antigas do Graphify ficam como historico.

## Outcome

- Signal: corrected
- Correction: A regra vale para o repositorio inteiro, nao so para o README.