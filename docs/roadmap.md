# Roadmap

- [x] API de instâncias, mensagens e webhooks; persistência, RabbitMQ e motor stub
- [x] Eventos confiáveis: entrega após commit, em ordem, com retry e teto de memória
- [x] Motor real (Cobalt 0.1.0, fork): QR/pareamento, sessão salva, envio, recebimento e status
- [x] Manager v1: login, instâncias, QR ao vivo, webhook, mensagem de teste
- [x] Manager: conversas e mensagens de cada instância (lidas do histórico)
- [x] Validar o motor Cobalt 0.1.0 ao vivo ([VALIDACAO-COBALT-FORK.md](VALIDACAO-COBALT-FORK.md))
- [x] Mais tipos de envio: áudio de voz, localização, contato, reação, enquete
- [ ] Figurinha (sticker), botões e listas
- [x] Chat e perfil: números no WhatsApp, marcar como lida, presença, foto, perfil e alterações
- [x] Grupos: criar, consultar, participantes, assunto, descrição, foto, configurações, convite e sair
- [x] Histórico de chat no banco (buscar mensagens e conversas, ADR 0008)
- [x] `ffmpeg` na imagem Docker (duração de áudio/vídeo, voz em OGG/Opus)
- [ ] Armazenamento de mídia (S3/MinIO)

## Manager, acesso e notificações (planejamento de 2026-11-13)

Visão do produto em `PRODUCT.md`; decisões nas ADRs 0010 a 0013.

- [x] **Fase 0, desenho:** `PRODUCT.md` e ADRs 0010 (organizações, usuários e papéis), 0011
  (notificações e e-mail), 0012 (outbox) e 0013 (tempo real com SSE)
- [x] **Fase 1, fundação:** outbox no PostgreSQL (entregas que sobrevivem a queda) e SSE no Manager
- [ ] **Fase 2, acesso:** organizações, usuários, papéis, login por e-mail, convite e redefinição
  de senha (SMTP), auditoria mínima
  - [x] base de e-mail (ADR 0011): SMTP pela outbox, modelos HTML e texto, tela Servidor com o
    estado do SMTP e o e-mail de teste
- [ ] **Fase 3, telas:** redesenho do Manager (lista, nova instância guiada com histórico no fluxo,
  visão geral, conversas, comportamento da conta, eventos agrupados com teste e últimas entregas,
  zona de perigo) e integrações e canais futuros como "Em breve"
  - [x] nova instância guiada: nome, QR ou código de pareamento, histórico (padrão "Sem histórico")
    e leitura do QR ao vivo; quem para no QR fica "Aguardando conexão" e o painel retoma
- [ ] **Fase 4, link de conexão:** página isolada do QR para o cliente, enviada por e-mail
- [ ] **Fase 5, alertas:** desconexão, sessão encerrada, webhook falhando, resumo do gestor
- [ ] Depois: 2FA, chaves de API com escopo por organização, proxy e comportamento por instância,
  WebSocket/SQS/Kafka para clientes
- [ ] Testes de contrato automatizados da API
