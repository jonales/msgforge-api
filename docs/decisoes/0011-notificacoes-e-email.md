# 0011: Notificações por e-mail (SMTP), com canais extensíveis

**Status:** proposta (2026-11-13). Depende da ADR 0012 (outbox) para a entrega e da ADR 0010 para
os destinatários.

## Contexto
O Manager não tem como avisar ninguém de nada: um número que desconecta só é percebido quando um
cliente reclama. A ADR 0010 também precisa de e-mail para convite e redefinição de senha. O
e-mail resolve as duas coisas e funciona com qualquer provedor por SMTP.

## Decisão
- **SMTP** pelo `spring-boot-starter-mail` (`JavaMailSender`), configurado pelo ambiente:
  `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM`, `SMTP_STARTTLS`.
  Sem `SMTP_HOST`, o e-mail fica desligado e o Manager diz isso onde importa (o "esqueci a senha"
  orienta a falar com o gestor).
- **Modelos** em Thymeleaf (`templates/email/`), com versão HTML e texto, e os textos em
  `messages.properties`, como o Manager.
- **Canal abstrato:** um `NotificationService` monta a notificação e a entrega por um
  `NotificationChannel`. O primeiro canal é o e-mail; webhook de alerta e mensagem de WhatsApp
  por uma instância de sistema podem vir depois sem mudar quem notifica.
- **Nunca no fluxo da requisição:** toda notificação vai para a outbox (ADR 0012). Convite e
  redefinição têm prioridade sobre alertas.
- **Tipos:**
  - acesso: convite, redefinição de senha, aviso de login novo;
  - alertas: instância desconectada (só depois de `ALERT_DISCONNECT_GRACE`, padrão 2 min, para não
    avisar a cada oscilação), sessão encerrada no celular (precisa de QR), webhook falhando N vezes
    seguidas, fila de entregas cheia, pedido de verificação do aparelho (passkey);
  - resumo diário ou semanal para o gestor (opcional);
  - envio do link de conexão (ADR 0010) para o cliente.
- **Sem enxurrada:** no máximo um alerta do mesmo tipo por instância a cada 30 min, e o alerta de
  "voltou a conectar" fecha o anterior.
- **Preferências** por usuário: quais alertas recebe, de quais organizações. O padrão é receber os
  alertas das instâncias que o papel permite ver.
- **"Enviar e-mail de teste"** e o estado do SMTP aparecem na tela de configurações do servidor.

## Alternativas consideradas
- **API de um provedor** (SES, SendGrid, Mailgun): entrega e métricas melhores, mas prende a um
  fornecedor. Esses provedores também aceitam SMTP, então quem quiser usa por aqui.
- **Envio direto, sem fila:** mais simples, mas uma falha do SMTP derruba a ação do usuário ou
  perde o alerta. Descartado.

## Consequências
- Entregabilidade (SPF, DKIM, domínio) é responsabilidade de quem opera o servidor; o
  `docs/configuracao.md` explica.
- Testes de integração com um SMTP em memória (GreenMail), sem rede.
- Variáveis novas em `docs/configuracao.md`. Nada muda no contrato da API.
