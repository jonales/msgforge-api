---
type: "query"
date: "2026-11-11T20:35:52.307260+00:00"
question: "como o historico de mensagens e gravado"
contributor: "graphify"
outcome: "useful"
source_nodes: ["HistoryRecorder", "MessageHistory", "ChatService"]
---

# Q: como o historico de mensagens e gravado

## Answer

HistoryRecorder escuta MessageReceived e MessageStatusUpdated e e chamado no created() do MessageController para os envios. MessageHistory usa NamedParameterJdbcTemplate (sem entidade JPA): INSERT ON CONFLICT ON CONSTRAINT uk_message_key DO NOTHING para reentregas; UPDATE de status so quando array_position do novo status e maior (READ nao volta a DELIVERY_ACK; HistoryIT.statusOnlyMovesForward pega a regressao). findChats usa DISTINCT ON (remote_jid). Mensagem de teste do Manager nao entra no historico.

## Outcome

- Signal: useful

## Source Nodes

- HistoryRecorder
- MessageHistory
- ChatService