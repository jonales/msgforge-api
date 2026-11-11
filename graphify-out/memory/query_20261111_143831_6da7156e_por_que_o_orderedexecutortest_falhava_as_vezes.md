---
type: "query"
date: "2026-11-11T14:38:31.665173+00:00"
question: "Por que o OrderedExecutorTest falhava as vezes?"
contributor: "graphify"
outcome: "corrected"
correction: "Sincronizar com latch em vez de presumir que a thread ja rodou."
source_nodes: ["OrderedExecutor"]
---

# Q: Por que o OrderedExecutorTest falhava as vezes?

## Answer

O teste presumia que a 1a tarefa ja tinha saido da fila quando submetia as seguintes; a virtual thread de drenagem podia nao ter comecado, a fila (limite 2) ficava cheia e a 3a tarefa era recusada. Correcao: latch 'started' dentro da 1a tarefa e esperar por ele. Validado com @RepeatedTest(300) temporario.

## Outcome

- Signal: corrected
- Correction: Sincronizar com latch em vez de presumir que a thread ja rodou.

## Source Nodes

- OrderedExecutor