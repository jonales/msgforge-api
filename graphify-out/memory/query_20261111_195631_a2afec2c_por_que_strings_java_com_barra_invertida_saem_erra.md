---
type: "query"
date: "2026-11-11T19:56:31.069393+00:00"
question: "Por que strings Java com barra invertida saem erradas quando escritas pelo shell?"
contributor: "graphify"
outcome: "corrected"
correction: "Usar Write/Edit, nao heredoc, para codigo com barras invertidas."
---

# Q: Por que strings Java com barra invertida saem erradas quando escritas pelo shell?

## Answer

Ao gerar arquivos Java com heredoc no Bash desta ferramenta, sequencias como \\; viraram \; (escape ilegal no Java). Para codigo com barras invertidas (regex, escapes de vCard), escreva com a ferramenta Write/Edit e compile em seguida.

## Outcome

- Signal: corrected
- Correction: Usar Write/Edit, nao heredoc, para codigo com barras invertidas.