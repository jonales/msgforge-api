# Teste do Cobalt 0.1.0 (fork `jonales/Cobalt`)

Programa mínimo, fora do Msgforge, para decidir se migramos o motor da 0.0.10 para a 0.1.0.
A pergunta principal: **o aviso "Não foi possível confirmar seu código de segurança" some?**
(pendência 5a do `CLAUDE.md`).

## Preparar (uma vez)

Precisa de JDK 25 e Maven. A 0.1.0 não está no Maven Central: instale o fork localmente (o `master`
já tem os ajustes do PR jonales/Cobalt#1):

```bash
git clone https://github.com/jonales/Cobalt.git
cd Cobalt
mvn -DskipTests -Dmaven.javadoc.skip=true install      # ~2 min
```

## Rodar

```bash
cd msgforge-api/docs/spikes/cobalt-0.1
mvn -q compile exec:java                       # sessão na pasta ./sessao
mvn -q compile exec:java -Dexec.args=outra     # outra pasta = outra sessão
```

Escaneie o QR no celular de teste (WhatsApp > Aparelhos conectados > Conectar um aparelho).
Comandos no terminal:

| Comando | O que faz |
|---|---|
| `existe 5561999999999` | consulta se o número tem WhatsApp (mostra o JID que o servidor devolve) |
| `enviar 5561999999999 oi` | consulta e envia pelo JID devolvido |
| `sair` | desconecta (a sessão continua salva) |

O terminal mostra `LOGADO`, `MENSAGEM` (com o número quando o remetente vem por LID), `STATUS` e
`DESCONECTADO`.

## O que observar (anote o resultado)

| # | Teste | Como | Esperado | 0.0.10 |
|---|---|---|---|---|
| 1 | **Código de segurança** | `enviar` para um segundo celular; abra a conversa nele | **nenhum aviso** de código de segurança | aviso em toda mensagem |
| 2 | Login | escanear o QR | `LOGADO` com `jid`, `lid` e `nome`; anote quantos segundos levou | ~60 s, `nome` nulo depois de restaurar |
| 3 | Status | depois do `enviar`, espere o celular receber e abrir | `STATUS ... SERVER_ACK`, depois `DELIVERY_ACK`/`READ` | só com contorno |
| 4 | Receber por LID | responda do celular | `MENSAGEM ... de=...@lid (número 55...)` | só com contorno |
| 5 | Nono dígito | `enviar` com o número **com** o 9 | `consulta` mostra o JID devolvido e a mensagem chega | cache envenenava |
| 6 | Envio repetido | `enviar` 3 vezes seguidas para o mesmo número | as 3 chegam | 2ª em diante sumia |
| 7 | Sessão restaurada | `sair` e rode de novo | "sessão salva encontrada: entrando sem QR" e `LOGADO` | só com contorno |
| 8 | Reenvio | (difícil de forçar) se aparecer "Aguardando mensagem" no celular | a mensagem chega sozinha depois | ignorado |

Se o teste 1 passar, a migração compensa. Se falhar, vale ligar o log de depuração e comparar com a
0.0.10 antes de decidir.

## Já corrigido no fork (achado ao montar este teste)

- `webClient().createConnection().name(...)` devolvia o tipo errado (`Mobile`): o exemplo do próprio
  README do Cobalt não compilava.
- O login por QR exigia a biblioteca nativa `libfido2` (de passkey) e quebrava em container Linux.
- Uma conexão que falha esperava 30 s no `disconnect()` e trocava o erro real por `SyncTimeout`.
