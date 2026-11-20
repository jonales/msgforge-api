# 0019: SMTP configurado pelo Manager

**Status:** aceita (2026-11-20). Revisa a parte de configuração da ADR 0011; canal, modelos e outbox
continuam como lá.

## Contexto

A ADR 0011 pôs o SMTP só no ambiente (`SMTP_*`). Trocar o servidor, a senha ou o remetente exigia editar
o `.env` e reiniciar, e a tela Servidor só mostrava o estado. O usuário quer configurar, testar e, depois,
editar os modelos de e-mail e os disparos pelo próprio Manager.

## Decisão

Em três entregas; esta ADR cobre a **primeira** (SMTP no banco) e fixa os limites das outras duas.

1. **Configuração no banco, uma linha só** (`smtp_settings`, `id = 1`), gravada por SQL direto, como o
   histórico e a auditoria: servidor, porta, usuário, senha cifrada, remetente, STARTTLS e timeout.
2. **Precedência:** a configuração do banco, quando existe, vale. Sem ela, vale o ambiente (`SMTP_*`),
   como antes; sem nenhuma das duas, o e-mail fica desligado. A tela diz de onde vem ("definida aqui" ou
   "do ambiente") e "Voltar ao ambiente" apaga a linha.
3. **Senha cifrada** pelo `SecretCipher` (ADR 0014), com um dono fixo para o SMTP. Ela nunca volta para a
   tela; em branco, a guardada fica. Sem `SECRETS_ENCRYPTION_KEY`, salvar **com** senha é recusado com o
   motivo; sem senha (servidor sem login) funciona.
4. **Vale na hora:** salvar remonta o `JavaMailSender`. O `OutboxWorker` passa a decidir **a cada rodada**
   se pega linhas `EMAIL` (antes decidia ao subir). Em mais de um nó, cada um relê a linha a cada minuto
   (pela data da última mudança).
5. **Testar antes de salvar:** conecta e faz o login no SMTP com o que está no formulário (senha em branco
   = a guardada), sem enviar nada. O erro sai com o motivo e nunca com a senha. Só o admin da plataforma
   (a mesma trava da tela); no máximo 10 testes por minuto, para a tela não virar varredura de rede.
6. **Auditoria** (ADR 0018): `SERVER_SMTP_UPDATED` e `SERVER_SMTP_REMOVED`, com o servidor no alvo e nunca a
   senha.

**Entregas seguintes (limites fixados agora):**
- **Modelos editáveis:** assunto e corpo guardados no banco por tipo de notificação, com **placeholders de
  uma lista fechada** (ex.: `{{nome}}`, `{{link}}`) trocados por valores escapados. **Nunca** Thymeleaf
  vindo do banco: modelo editado por alguém que executa expressão no servidor é injeção de template. Sem
  modelo no banco, vale o arquivo de `templates/email/`.
- **Disparos:** por tipo de notificação, ligado ou não e para quem, com a janela contra repetição da ADR 0011.

## Consequências

- Quem já usa `SMTP_*` não muda nada: sem linha no banco, o ambiente continua valendo.
- O teste de conexão abre uma conexão de saída a pedido de um admin; o limite por minuto e a trava de
  admin seguram o abuso, mas o endereço não passa pelo `PrivateNetworkGuard` (um relay interno é uso
  legítimo de SMTP).
- Os testes de integração dividem o banco: quem grava `smtp_settings` apaga no fim, ou os outros contextos
  passariam a pegar linhas `EMAIL`.
