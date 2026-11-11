---
type: "query"
date: "2026-11-11T18:49:44.419950+00:00"
question: "Por que o Manager respondia 401 em vez de redirecionar para o login?"
contributor: "graphify"
outcome: "corrected"
correction: "Usar um entry point unico que decide por HX-Request."
source_nodes: ["ManagerSecurityConfig"]
---

# Q: Por que o Manager respondia 401 em vez de redirecionar para o login?

## Answer

exceptionHandling().defaultAuthenticationEntryPointFor(entryPoint, matcher) registrado antes do form login virou o entry point padrao (DelegatingAuthenticationEntryPoint usa o primeiro como default), entao todo acesso sem sessao recebia o 401 pensado so para o htmx. Solucao: authenticationEntryPoint(...) unico que checa HX-Request e senao delega a LoginUrlAuthenticationEntryPoint. Pego por ManagerIT.everythingButLoginAndAssetsRequiresTheManagerSession.

## Outcome

- Signal: corrected
- Correction: Usar um entry point unico que decide por HX-Request.

## Source Nodes

- ManagerSecurityConfig