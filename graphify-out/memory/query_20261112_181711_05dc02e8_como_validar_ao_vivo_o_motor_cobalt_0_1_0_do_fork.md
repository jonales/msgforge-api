---
type: "query"
date: "2026-11-12T18:17:11.564812+00:00"
question: "Como validar ao vivo o motor Cobalt 0.1.0 do fork"
contributor: "graphify"
outcome: "useful"
source_nodes: ["CobaltWhatsAppGateway"]
---

# Q: Como validar ao vivo o motor Cobalt 0.1.0 do fork

## Answer

Roteiro em docs/VALIDACAO-COBALT-FORK.md (PR #12, branch claude/elegant-faraday-6i4y7z): docker compose com MSGFORGE_GATEWAY=cobalt e log DEBUG; conectar pelo Manager medindo o tempo de login; 3 sendText para um segundo celular conferindo se some o aviso 'Nao foi possivel confirmar seu codigo de seguranca'; resposta com numero (nao LID) e status; sendMedia; restart sem QR. Testes 2 (login) e 3 (codigo de seguranca) decidem a migracao; com eles bons, tirar o item 5 do CLAUDE.md e aceitar a ADR 0007. Nao roda na nuvem (proxy bloqueia o WhatsApp).

## Outcome

- Signal: useful

## Source Nodes

- CobaltWhatsAppGateway