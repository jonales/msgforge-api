---
type: "query"
date: "2026-11-11T14:00:31.238318+00:00"
question: "Por que o JDK HttpClient falhava com receptores de webhook simples?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["HttpClientConfig", "WebhookSender"]
---

# Q: Por que o JDK HttpClient falhava com receptores de webhook simples?

## Answer

Duas causas: corpo enviado chunked (sem Content-Length) e tentativa de upgrade h2c em http:// do JDK HttpClient. HttpClientConfig fixa HTTP/1.1 e WebhookSender serializa o corpo para byte[], o que gera Content-Length. Coberto por WebhookSenderTest.sendsJsonWithContentLengthAndCustomHeaders.

## Outcome

- Signal: useful

## Source Nodes

- HttpClientConfig
- WebhookSender