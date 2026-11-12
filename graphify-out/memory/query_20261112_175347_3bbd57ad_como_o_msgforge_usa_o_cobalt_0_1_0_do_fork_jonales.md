---
type: "query"
date: "2026-11-12T17:53:47.084914+00:00"
question: "Como o Msgforge usa o Cobalt 0.1.0 do fork jonales/Cobalt"
contributor: "graphify"
outcome: "useful"
source_nodes: ["CobaltWhatsAppGateway", "LidResolver", "SentStatusTracker", "CobaltMessageMapper"]
---

# Q: Como o Msgforge usa o Cobalt 0.1.0 do fork jonales/Cobalt

## Answer

Dependencia com.github.auties00:cobalt-lib:0.1.0 instalada por scripts/install-cobalt.sh (commit fixo; CI, Dockerfile e hook rodam antes da build). API bloqueante: o gateway chama por call(...) com teto COBALT_SEND_TIMEOUT_MS. Sessao em disco por instancia (COBALT_SESSION_DIR, volume /data/cobalt); session_store guarda so o marcador COBALT-FS1. Handlers de QR/pairing precisam de passkeyAuthenticator (link do checkpoint vai para o log). Antes do login, erro de conexao vira DISCONNECT no errorHandler (senao o Cobalt fica tentando e o connect espera o qrWaitMs). Midia: build -> uploadMedia(provider, stream) -> sendMessage. Removidos os contornos da 0.0.10 (WebVersion, OfflineQueueGuard, RawNodes, UsersCache, DatabaseSessionSerializer, shim aspose).

## Outcome

- Signal: useful

## Source Nodes

- CobaltWhatsAppGateway
- LidResolver
- SentStatusTracker
- CobaltMessageMapper