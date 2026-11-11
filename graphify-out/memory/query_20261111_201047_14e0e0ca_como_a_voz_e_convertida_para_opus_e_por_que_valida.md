---
type: "query"
date: "2026-11-11T20:10:47.129361+00:00"
question: "como a voz e convertida para opus e por que validar video"
contributor: "graphify"
outcome: "useful"
source_nodes: ["AudioTranscoder", "MediaSignatures", "CobaltWhatsAppGateway"]
---

# Q: como a voz e convertida para opus e por que validar video

## Answer

AudioTranscoder (gateway/media) chama o ffmpeg por stdin/stdout, le stdout e stderr em paralelo com limite e mata o processo no timeout; so converte se encode=true e o audio ainda nao e OGG/Opus, e sem ffmpeg envia como veio. MediaSignatures recusa video sem ftyp/EBML antes do Cobalt chamar o ffprobe, que com bytes invalidos espera ~10 s. Testes usam o ffmpeg real e sao pulados (assumeTrue) sem ele; o CI instala o ffmpeg.

## Outcome

- Signal: useful

## Source Nodes

- AudioTranscoder
- MediaSignatures
- CobaltWhatsAppGateway