# Arquitetura

```
dev.msgforge
├── instance/   rotas /instance, serviço de instâncias e estado (Redis + banco)
├── message/    rotas /message e MessageService
├── chat/       rotas /chat: números no WhatsApp, leitura, presença e perfil
├── group/      rotas /group: grupos, participantes e convites
├── history/    histórico de mensagens no banco (gravado a partir dos eventos)
├── webhook/    rotas /webhook e configuração por instância
├── event/      eventos do motor → envelope → webhook / RabbitMQ (ordenado, com retry)
├── gateway/    WhatsAppGateway: única fronteira com o protocolo (stub e cobalt/)
├── security/   header apikey e proteção contra webhooks internos
├── web/        Manager (Thymeleaf + htmx, Spring Security só em /manager)
└── common/, config/
```

Recursos do Java 25 usados: virtual threads, records, `sealed interface` com pattern matching
exaustivo nos eventos e unnamed patterns. As decisões de arquitetura estão em
[decisoes/](decisoes/).

## O que a aplicação faz, em detalhe

- **Instâncias:** criar, conectar (QR code em texto e imagem, ou código de pareamento), consultar
  o estado, listar, desconectar e excluir. A sessão é salva pelo motor (no Cobalt, em
  disco, uma pasta por instância): a aplicação reinicia e a instância reconecta sozinha, sem QR.
- **Mensagens:** texto (com "digitando..." opcional), mídia (imagem, vídeo, áudio e documento) por
  URL ou base64, áudio de voz, localização, contatos (vCard), reações e enquetes.
- **Chat e perfil:** verificar quais números têm WhatsApp, marcar como lida, presença
  ("digitando...", "gravando..."), foto e perfil de contatos, e alterar nome, recado e foto da conta.
  Histórico das mensagens no banco, com busca paginada e lista de conversas.
- **Grupos:** criar, consultar, listar, gerenciar participantes e admins, assunto, descrição, foto,
  quem pode mandar mensagem ou editar, convites e sair.
- **Eventos:** QR code, conexão, mensagem enviada, mensagem recebida e status de entrega/leitura.
  Saem por webhook da instância, webhook global e RabbitMQ:
  - entregues depois que a operação é gravada no banco;
  - **em ordem** por instância;
  - com timeout e retry exponencial.
- **Segurança:**
  - Chave global ou token por instância (header `apikey`).
  - Bloqueio opcional de webhooks para a rede interna.
  - Manager com sessão, CSRF, CSP e limite de tentativas de login.
- **Manager:** painel em `/manager`, renderizado no servidor (Thymeleaf + htmx), sem build de
  front-end: instâncias, QR ao vivo, webhook, mensagem de teste e as conversas de cada instância.
