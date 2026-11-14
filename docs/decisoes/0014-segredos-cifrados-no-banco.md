# 0014: Segredos cifrados no banco (senha do proxy da instância)

**Status:** aceita (2026-11-13).

## Contexto
O `POST /instance/create` aceita o proxy da instância (`proxyHost`, `proxyPort`, `proxyProtocol`,
`proxyUsername`, `proxyPassword`). O motor precisa da senha em claro a cada conexão (inclusive ao
restaurar a sessão no boot), então ela tem de ser guardada. Em texto aberto, qualquer dump ou
backup do banco entregaria a credencial do proxy.

## Decisão
- A senha vai para `instance.proxy_password_encrypted` cifrada com **AES-256-GCM**
  (`security/SecretCipher`), com IV aleatório de 12 bytes e o id da instância como dado
  associado: um valor copiado para outra instância não abre. Formato: `v1:` + base64(IV || cifrado).
- A chave vem de `SECRETS_ENCRYPTION_KEY` (32+ caracteres; a chave AES é o SHA-256 dela), separada
  de `AUTHENTICATION_API_KEY`: trocar a chave da API não deve invalidar as senhas guardadas.
- **Sem a chave**, um create com `proxyPassword` responde 400 (nunca grava em claro nem descarta a
  senha calada). Proxy sem senha funciona sem a chave.
- A senha só é decifrada no gateway do Cobalt, ao montar a conexão. Não aparece em respostas
  (`fetchInstances` não mostra o proxy), em `toString()` (DTO e `InstanceProxy` mascaram) nem em
  mensagens de erro.

## Consequências
- Trocar ou perder `SECRETS_ENCRYPTION_KEY` deixa as senhas guardadas ilegíveis: o connect dessas
  instâncias falha com 500 (mensagem sem o segredo) até a instância ser recriada. Não há rotação.
- O valor `v1:` deixa espaço para outro esquema (rotação, KMS) sem migrar o formato de uma vez.
- Outros segredos que vierem a ser guardados (ex.: senha SMTP da ADR 0011) usam o mesmo
  `SecretCipher`.
