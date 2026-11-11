---
type: "query"
date: "2026-11-11T18:27:28.190400+00:00"
question: "Por que o Thymeleaf da EL1004E Method cannot be found em um record do Manager?"
contributor: "graphify"
outcome: "corrected"
correction: "Modelos de tela do Thymeleaf devem ser publicos."
source_nodes: ["ManagerController", "ManagerViews"]
---

# Q: Por que o Thymeleaf da EL1004E Method cannot be found em um record do Manager?

## Answer

O SpEL do Thymeleaf so chama metodos de tipos publicos; records aninhados em classe package-private (ManagerViews) falham com EL1004E na renderizacao, que acontece depois do controller e por isso nao passa pelo ManagerExceptionHandler (vira 500/whitelabel). Solucao: ManagerViews e seus records/metodos publicos. Cobrir cada tela no ManagerIT.

## Outcome

- Signal: corrected
- Correction: Modelos de tela do Thymeleaf devem ser publicos.

## Source Nodes

- ManagerController
- ManagerViews