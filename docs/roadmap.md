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
- [ ] Testes de contrato automatizados da API
