---
type: "query"
date: "2026-11-12T14:53:17.786907+00:00"
question: "por que mensagem recebida sem status sumia do historico"
contributor: "graphify"
outcome: "useful"
source_nodes: ["MessageHistory", "HistoryRecorder"]
---

# Q: por que mensagem recebida sem status sumia do historico

## Answer

MessageHistory.save usava STATUS_ORDER.contains(status) e updateStatus usava indexOf(status); STATUS_ORDER e List.of, que lanca NullPointerException com null. O HistoryRecorder engolia a excecao (log com mensagem null) e a mensagem recebida sem campo status nao era gravada. O Cobalt sempre manda status, entao so apareceu no ManagerIT. Corrigido checando null antes; HistoryIT.messagesWithoutStatusAreStillRecorded cobre.

## Outcome

- Signal: useful

## Source Nodes

- MessageHistory
- HistoryRecorder