# 0015: Bot com IA por instância

**Status:** aceita (2026-11-15).

## Contexto
A tela Integrações de cada instância mostrava só itens "Em breve". O usuário escolheu o bot com IA
como a primeira integração real. Ele responde sozinho às conversas com um modelo de linguagem, e o
cliente decide quando o bot entra e quando sai. Dify, Flowise e Typebot dependem da mesma base:
quando responder, memória da conversa e pausa quando um atendente assume. Essa base nasce aqui.

## Decisão
- **Um bot por instância** (`instance_bot`), configurado no Manager (Integrações → Bot com IA), fora
  do contrato da API. Pede a permissão `ACCOUNT_CONFIGURE`.
- **Provedor compatível com a API da OpenAI** (`POST {base}/chat/completions`): serve para a OpenAI e
  para qualquer servidor compatível (Ollama, vLLM, LM Studio). O campo `provider` deixa lugar para
  Dify, Flowise e Typebot. A chave vai cifrada (ADR 0014) e nunca volta para a tela.
- **Quando o bot entra** (o cliente escolhe):
  - `ALL`: toda conversa;
  - `KEYWORD`: a mensagem contém uma das palavras-chave;
  - `EXACT`: a mensagem é exatamente uma delas.

  Depois de entrar, o bot segue respondendo naquela conversa até a palavra de encerrar, até o
  tempo sem mensagens passar (`session_minutes`; 0 = não expira) ou até ser pausado. Os grupos ficam
  de fora por padrão.
- **Quando um atendente assume**, o bot pausa naquela conversa (`pause_on_human`, ligado por padrão).
  Assumir quer dizer responder pelo Manager ou pelo celular. O envio pela API não pausa, porque
  costuma ser a própria automação do cliente.

  Como o bot volta (o cliente escolhe):
  - `AUTOMATIC`: depois de `pause_minutes`;
  - `MANUAL`: só quando alguém reativar.

  Na conversa do Manager, quem pode enviar mensagens vê o estado do bot e pode pausar ou reativar à
  mão.
- **Memória:** as últimas `context_messages` (até 50) mensagens de texto da conversa, lidas do
  histórico (ADR 0008), mais as instruções do cliente como mensagem de sistema. Só texto entra e sai.
  Mídia é ignorada.
- **Estado por conversa** em `bot_session` (ACTIVE, PAUSED, CLOSED; `paused_until` nulo = pausa
  manual), no banco: vale com mais de um servidor.
- **Ordem:** as mensagens de uma conversa passam uma por vez (`OrderedExecutor` por instância e
  conversa), em threads virtuais, fora da thread do evento.
- **Autoria:** o que o bot envia entra no histórico com `sent_by = BOT`. O eco do próprio envio, que
  chega logo depois, não conta como atendente (há uma janela curta por conversa).
- **Rede:** o endereço do modelo é digitado pelo cliente. Endereços privados são recusados, salvo com
  `BOT_ALLOW_PRIVATE_NETWORKS=true` (para um Ollama na mesma máquina). Sem redirecionamento, com
  tempo limite (`BOT_TIMEOUT_MS`). A resposta do modelo e os erros não vão para o WhatsApp; os erros
  vão para o log, sem a chave.

## Consequências
- Cada mensagem recebida numa instância com bot faz uma consulta ao banco e, se o bot responder, uma
  chamada ao modelo. O custo do provedor é do cliente.
- Não junta mensagens enviadas em sequência: cada uma recebe resposta. A janela de agrupamento fica
  para depois, junto com botões e listas (para o Typebot).
- `BOT_ENABLED=false` desliga todos os bots do servidor.
