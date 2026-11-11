---
type: "query"
date: "2026-11-11T18:49:44.326196+00:00"
question: "Por que o limitador de login contava duas falhas por tentativa?"
contributor: "graphify"
outcome: "corrected"
correction: "Nao ter efeito colateral em AuthenticationProvider; contar em handlers."
source_nodes: ["ManagerSecurityConfig", "LoginAttemptLimiter"]
---

# Q: Por que o limitador de login contava duas falhas por tentativa?

## Answer

O Spring Boot registra o AuthenticationProvider bean tambem no AuthenticationManager pai (global). Numa BadCredentialsException o ProviderManager local repassa ao pai, que chama o mesmo provider de novo: qualquer efeito colateral no provider roda 2x (limite efetivo virou 5). Solucao: provider so verifica (blocked), a contagem fica no failure handler e o reset no success handler, que rodam uma vez por requisicao. LockedException (AccountStatusException) nao vai para o pai.

## Outcome

- Signal: corrected
- Correction: Nao ter efeito colateral em AuthenticationProvider; contar em handlers.

## Source Nodes

- ManagerSecurityConfig
- LoginAttemptLimiter