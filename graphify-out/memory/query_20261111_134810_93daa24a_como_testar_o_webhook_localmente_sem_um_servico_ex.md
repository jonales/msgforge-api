---
type: "query"
date: "2026-11-11T13:48:10.670404+00:00"
question: "Como testar o webhook localmente sem um serviço externo?"
contributor: "graphify"
outcome: "useful"
---

# Q: Como testar o webhook localmente sem um serviço externo?

## Answer

Use um receptor HTTP/1.1 (Python http.server com protocol_version='HTTP/1.1') que decodifique Transfer-Encoding: chunked e responda com Content-Length: 0. O RestClient envia o corpo chunked; um receptor HTTP/1.0 ingênuo recebe o corpo vazio e o cliente falha com 'header parser received no bytes'.

## Outcome

- Signal: useful