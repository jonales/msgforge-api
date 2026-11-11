---
type: "query"
date: "2026-11-11T18:27:28.293284+00:00"
question: "Como o Spring Security convive com a API no estilo Evolution?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["ManagerSecurityConfig", "ApiKeyFilter"]
---

# Q: Como o Spring Security convive com a API no estilo Evolution?

## Answer

ManagerSecurityConfig: cadeia @Order(1) com securityMatcher /manager/** (form login com a chave global em tempo constante, CSRF, sessao, CSP so 'self' + data: para imagem; htmx com includeIndicatorStyles false) e cadeia @Order(2) para todo o resto com permitAll, csrf/sessao/headers/requestCache desligados, para a API nao mudar (o ApiKeyFilter ignora /manager). ManagerIT.theApiIsUntouchedByTheManagerSecurity garante sem Set-Cookie e sem X-Frame-Options na API. Spring Security 7 redireciona para o login com caminho relativo (/manager/login).

## Outcome

- Signal: useful

## Source Nodes

- ManagerSecurityConfig
- ApiKeyFilter