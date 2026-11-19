# 0018: Auditoria do Manager

**Status:** aceita (2026-11-19)

## Contexto

A ADR 0010 pede uma "auditoria mínima": quem, o quê, em qual organização, quando e de qual IP. A
permissão "ver auditoria" (`AUDIT_VIEW`) já existia no catálogo, marcada "Em breve". A pesquisa foi
feita pela mesa de especialistas locais (dossiê `20261118-025509`); a decisão conferiu os pontos no
texto da OWASP (Logging Cheat Sheet e Authentication Cheat Sheet):

- registrar "when, where, who and what" de cada evento;
- registrar as falhas e os sucessos de autenticação;
- não registrar o que ajuda a adivinhar contas ou senhas (a senha, o texto digitado no lugar errado).

## Decisão

1. **O que entra:** login (`OK`, `FAILED`, `BLOCKED` quando o IP passou do limite de tentativas,
   `DISABLED` quando a senha está certa mas o usuário está bloqueado) e logout; e as ações de pessoas e
   papéis que dão certo: criar usuário, trocar papel, bloquear, reativar, mudar o nome de exibição, criar,
   alterar e excluir papel. Ação recusada pelas travas (escalada, último gestor) não entra.
2. **Tabela `audit_event`** por SQL direto (`audit/AuditLog`), sem entidade JPA e **sem chave
   estrangeira**: o registro sobrevive a quem foi apagado. Quem agiu fica também em texto
   (`actor_label`, o e-mail da hora). A chave global aparece como `@chave`.
3. **Login que falhou:** guarda a conta só se ela existir (id e e-mail). Sem conta, `@desconhecida`,
   **sem** o texto digitado, que pode ser uma senha no campo errado. Nunca senha nem chave.
4. **IP bloqueado:** um registro por IP a cada janela do limitador (15 min), não um por tentativa; sem
   isso, quem insiste enche a tabela.
5. **Gravar nunca derruba a ação:** dentro de transação, o registro sai depois do commit, numa transação
   própria; falha vai só para o log.
6. **Retenção de 180 dias** (`AUDIT_RETENTION_DAYS`, `AuditRetention` de hora em hora, em lotes): o IP é
   dado pessoal (LGPD). 0 guarda para sempre.
7. **Tela `/manager/audit`** com `AUDIT_VIEW`: cada pessoa vê as organizações em que tem a permissão
   (as ações feitas nelas e os logins e logouts de quem é membro); o admin da plataforma vê também a
   plataforma inteira, a única visão com as tentativas de conta inexistente e as da chave global.
   Filtros: tudo, entradas e saídas, falhas e bloqueios, pessoas e papéis; 50 por página.
8. **Mudança de papel** guarda o que entrou e saiu (`+CHAT_SEND -USERS_MANAGE`, nomes do catálogo, que a
   tela traduz); troca de papel de usuário guarda "antigo → novo".

## Consequências

- Ação nova a auditar: valor em `AuditAction`, migration no `CHECK` de `audit_event` e o texto
  `manager.audit.action.*`.
- O IP é o `getRemoteAddr()`: atrás de proxy, depende do `TRUSTED_PROXIES` (como os limitadores).
- Sessão que expira e sessão derrubada pelo bloqueio (`ActiveSessionFilter`) não geram logout.
- Ficam de fora, por ora: ações nas instâncias (criar, conectar, excluir, configurações), o convite e o
  "esqueci a senha" (entram com eles) e a exportação dos registros.
