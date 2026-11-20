# Validação ao vivo: motor Cobalt 0.1.0 (fork)

Roteiro para validar, com celulares de verdade, o Cobalt 0.1.0 de `jonales/cobalt-2` (commit
fixado em `scripts/install-cobalt.sh`). Rode de novo a cada commit novo do fork.
Não dá para rodar na nuvem: o proxy bloqueia o WhatsApp. Leva uns 30 minutos.

**O que mais importa:** o teste 3 (aviso de código de segurança, no celular **e** no WhatsApp
Web), o 4 (recibos e mensagens de aparelho vinculado) e o 2 (tempo de login). O spike da
[ADR 0009](decisoes/0009-spike-comparativo-motores.md), feito com o Cobalt original antes do
fork, viu falhar justamente o aviso no Web, o recebimento de aparelho vinculado e os recibos.

## Antes de começar

- Dois celulares com WhatsApp: **A** (vira a instância do Msgforge) e **B** (recebe e responde).
  Use números de teste, não o seu número principal.
- Docker com Compose. A build instala o fork sozinha (`scripts/install-cobalt.sh`); não precisa
  de JDK nem de Maven na máquina.
- Uma pasta de anotações para os resultados (a tabela do fim deste arquivo).

## 1. Subir a stack

```bash
git clone https://github.com/jonales/msgforge-api.git   # ou: git pull no main
cd msgforge-api
cp .env.example .env
```

No `.env`, ajuste:

```
AUTHENTICATION_API_KEY=<saída de: openssl rand -hex 32>
MSGFORGE_GATEWAY=cobalt
LOGGING_LEVEL_DEV_MSGFORGE_GATEWAY_COBALT_WIRE=DEBUG
```

```bash
docker compose up -d --build          # a primeira build leva alguns minutos (compila o fork)
docker compose logs -f msgforge       # deixe este terminal aberto: é o log do teste
```

Se você já usava o Msgforge com a 0.0.10, as instâncias antigas vão pedir QR de novo: o formato da
sessão mudou. Isso é esperado.

## 2. Conectar (teste de login)

1. Abra `http://localhost:8080/manager`, entre com a `AUTHENTICATION_API_KEY`.
2. Crie a instância `teste` e clique em conectar.
3. **Marque o horário** e escaneie o QR com o celular **A** (WhatsApp > Aparelhos conectados >
   Conectar um aparelho).
4. Espere o status ficar "conectada" e **anote quantos segundos levou** desde o scan. No log,
   procure `[cobalt] teste: conectada como ...`.

Esperado: bem menos que os ~60 s da 0.0.10.

## 3. Enviar texto (teste do código de segurança)

```bash
curl -X POST http://localhost:8080/message/sendText/teste \
  -H "apikey: $AUTHENTICATION_API_KEY" -H "Content-Type: application/json" \
  -d '{"number":"55DDDNUMERO_DO_B","text":"teste 1"}'
```

Repita mais duas vezes (`teste 2`, `teste 3`). No celular **B**, abra a conversa. Abra também
o WhatsApp Web de **B** (web.whatsapp.com, vinculado ao celular B) na mesma conversa.

Esperado: as 3 mensagens chegam e **não aparece** "Não foi possível confirmar seu código de
segurança", nem no celular nem no Web. Se aparecer, anote onde (celular, Web ou os dois) e em
qual mensagem (a 1ª ou todas), e guarde o log desse trecho.

**Sessão Signal (a pista da 0.0.10):** no log, cada envio aparece como
`-> message{...} [participants{} [to{jid=...} [enc{type=pkmsg, ...}]] ...]`. Depois que **B
responder** (teste 4), envie mais um texto: o `enc` para o aparelho de B deve passar a
`type=msg`. Se continuar `pkmsg` em todo envio, a sessão não está se fixando (era o sintoma da
0.0.10); anote e guarde o log.

## 4. Receber e status

1. Responda do celular **B**. Depois responda também pelo **WhatsApp Web de B** (aparelho
   vinculado): no log, procure se a mensagem foi decifrada ou se o Cobalt pediu reenvio
   (`receipt type=retry`).
2. No Manager, abra Mensagens da instância `teste`: a resposta deve aparecer, com o número do B
   (não um `...@lid`).
3. Ainda nas Mensagens, confira o status das enviadas: deve avançar para entregue e, depois que B
   abrir a conversa, para lida.

Pelo terminal, a mesma conferência:

```bash
curl -X POST http://localhost:8080/chat/findMessages/teste \
  -H "apikey: $AUTHENTICATION_API_KEY" -H "Content-Type: application/json" -d '{}'
```

## 5. Mídia

```bash
curl -X POST http://localhost:8080/message/sendMedia/teste \
  -H "apikey: $AUTHENTICATION_API_KEY" -H "Content-Type: application/json" \
  -d '{"number":"55DDDNUMERO_DO_B","mediatype":"image","mimetype":"image/png","caption":"foto","media":"https://www.w3.org/Icons/w3c_home.png"}'
```

Esperado: a imagem chega com a legenda. Se falhar, guarde o erro do log: o envio de mídia do fork
converte o arquivo antes de subir, e isso ainda não foi exercitado na imagem Docker.

## 6. Sessão restaurada

```bash
docker compose restart msgforge
```

Esperado: a instância volta "conectada" **sem pedir QR**, e um novo `sendText` funciona.
Atenção: `docker compose down -v` apaga o volume `cobalt_sessions` e obriga a escanear de novo.

## 7. Se algo der errado

- Guarde o log: `docker compose logs msgforge > log-validacao.txt` (com o `DEBUG` acima, o log
  traz só nomes e atributos dos nós, nunca o conteúdo das mensagens).
- Se o log mostrar "o WhatsApp pediu a passkey do aparelho", abra o link no celular A.
- Para testar outro commit do motor, troque o padrão de `COBALT_REF` em
  `scripts/install-cobalt.sh` e rode `docker compose up -d --build` de novo (a build do Docker
  não repassa a variável de ambiente; fora do Docker, `COBALT_REF=<commit> scripts/install-cobalt.sh`
  funciona).

## Rodada de 2026-11-12/13

Docker local, instâncias `acerto` (conta Business, A) e `Principal-` (B, com o WhatsApp Web
aberto no PC). Commit final do fork: `51f2d36` (branch `fix/haswhatsapp-usync-interactive`,
PR jonales/Cobalt#4).

| # | Teste | Resultado |
|---|---|---|
| 2 | Login após o scan | ✅ 2 a 5 s |
| 3 | Aviso no celular de B | ✅ não aparece |
| 3 | Aviso no WhatsApp Web de B | ✅ não aparece desde o `51f2d36` (antes, a cada mensagem) |
| 3 | 3 textos seguidos chegam | ✅ |
| 4 | Envio depois da resposta sai como `enc type=msg` | ✅ |
| 4 | Resposta do B aparece com o número (não LID) | ✅ |
| 4 | Resposta pelo Web de B é decifrada | ✅ |
| 4 | Status entregue / lida | ✅ até READ |
| 5 | Imagem com legenda chega | ✅ PNG e JPEG |
| 6 | Volta sem QR após restart | ✅ ~2 s |
| — | Fila offline com stories de contatos | ✅ |
| — | Nota de voz (MP3, OGG/Opus, `encoding: false`) | ✅ baixa e toca desde o `4795130` (antes chegava e não baixava) |
| — | Áudio como arquivo (`sendMedia`, MP3) | ✅ baixa e toca desde o `bc2dec2` (antes, 500 no envio) |

**O aviso no WhatsApp Web** vinha do `deviceListMetadata` (ICDC), que vai em toda mensagem com o
`ts` da lista de aparelhos do remetente. O fork mandava o horário da consulta (e em
milissegundos), não o `ts` assinado da lista de índices de chave: o Web de quem recebe esperava
uma lista mais nova que a assinada e avisava. Como foi isolado: mandar sem o ICDC tirou o aviso;
um log de diagnóstico mostrou o `ts` igual ao horário do envio nas duas contas e o `key-index`
zerado em todos os aparelhos; com os três fixes (segundos, `key-index` do `<device-list>`, `ts`
assinado), o ICDC volta e o aviso não aparece. A condição foi conferida no JS público do WhatsApp
Web (`WAWebHandleAdvKeyIndexResultApi`, `WAWebUsyncDevice`, `WAWebIdentityIcdcApi`).

**O áudio** tinha dois bugs no transcodificador do fork (ffmpeg nativo, que lê MP3 e OGG). A nota
de voz era gerada com o *granule* do OGG/Opus a 16 kHz, e não a 48 kHz: o arquivo dizia durar um
terço do real e o WhatsApp não o baixava. O áudio comum ia ao codificador AAC em quadros de
tamanho variável e falhava quando precisava de reamostragem.

**Para diagnosticar o motor:** `COBALT_LOG_LEVEL=WARNING` (ou `DEBUG`) no `.env` liga os logs
internos do Cobalt, que ficam desligados sem essa variável.

## Rodada de 2026-11-15

Duas instâncias (uma conta comum e uma Business), motor no cobalt-2 com o PR #4.

| Item | Resultado |
|---|---|
| Vídeo (`sendMedia`, MP4 H.264 de 5 s) | ✅ chegou com miniatura, duração e som. Antes, nenhum vídeo saía (cobalt-2#4: perfil e nível do H.264, `+faststart` e miniatura JFIF) |
| Foto de grupo (`updateGroupPicture`) | ✅ aceita pelo servidor e notificada aos dois aparelhos. O 406 era a imagem: agora vai como JPEG 640×640, sem EXIF, igual ao recorte do WhatsApp Web (#55) |
| Nome no `fetchProfile` de outro número | ✅ "4C3RT0 Tecnologia" e o nome da conta comum. Antes vinha `null` (#55) |
| `MESSAGES_DELETE` (apagar para todos) | ✅ nas duas instâncias, com `status: DELETED` |
| `PRESENCE_UPDATE` | ✅ `composing` e `available` |
| `CALL` | ✅ oferta e recusa automática (`rejectCall`) |
| `CHATS_UPDATE` (arquivar) | ⚠️ chegou, mas com 17 eventos, um por conversa, por causa da ressincronização do app state. Filtrado no #56 (ainda não visto ao vivo) |
| `CONTACTS_UPDATE` (troca de recado) | ❌ não saía. Corrigido no #56 (ainda não visto ao vivo) |
| Bot com IA (Ollama `qwen2.5:7b`) | ✅ responde à palavra-chave, continua a conversa sem ela e pausa quando o atendente responde pelo Manager |
| Assinatura do atendente | ✅ nome em negrito na primeira linha; o formato "Atendente {nome}" veio depois (#56) |

Falta ver ao vivo: `CONTACTS_UPDATE` e o filtro de `CHATS_UPDATE` do #56, o modelo de assinatura,
`MESSAGES_SET` (histórico no pareamento) e `CHATS_DELETE`.

## Rodada de 2026-11-15 (segunda parte)

Motor no cobalt-2 com os PRs #9, #10 e #11.

| Item | Resultado |
|---|---|
| `CONTACTS_UPDATE` (troca de recado e de foto) | ✅ chega, mas repetido (até 3 vezes no recado) |
| Filtro do `CHATS_UPDATE` | ✅ depois do primeiro retrato, 1 evento por ação. Numa conexão nova, ainda 1 por conversa (85 ao parear) |
| Assinatura "Atendente {nome}" | ✅ |
| Bot (pausa, volta sozinho, palavra de encerrar) | ✅ |
| `CHATS_DELETE` | ✅ chega, junto com exclusões antigas reenviadas |
| Foto da própria conta (`updateProfilePicture`) | ✅ depois do cobalt-2#9 (antes: 30 s e a foto não mudava). Troca em 2 s |
| Status de contato e `readStatus` | ✅ depois do cobalt-2#10 (antes o motor descartava o `<status>`). O mesmo status sai duas vezes |
| Logout | ✅ depois do cobalt-2#11, a conexão fecha logo depois do desvínculo (antes: 47 s) |
| `MESSAGES_SET` (histórico "Recente") | ✅ o evento chega; o histórico vem em partes |

## Rodada de 2026-11-19

Duas instâncias conectadas (TESTE-1 e TESTE-2), motor no cobalt-2 `cd7b1919`, eventos num receptor de
webhook local. Conferido no aparelho pelo usuário: mapa, PDF, nota de voz, imagem e cartão de contato.

| Item | Resultado |
|---|---|
| `sendLocation` | ✅ o mapa abre no ponto; `locationMessage` com coordenadas, nome e endereço |
| `sendContact` (um e dois contatos) | ✅ envio. No celular, "adicionar contato"; no Web, "adicionar a grupo" (o Web mostra isso para vCard com `waid`). ❌ Recebido: o `messages.upsert` sai sem o `vcard` (só o nome) |
| `sendPoll` | ✅ voto único. ❌ O voto chega como `pollUpdateMessage: {}`, sem a chave da enquete nem a opção |
| `sendMedia` documento (PDF com `fileName`) | ✅ |
| `sendMedia` vídeo H.264 1080p60 | ✅ |
| `sendMedia` vídeo HEVC/H.265 1080p | ❌ 500: o ffmpeg embutido no motor não tem o decodificador HEVC (`avcodec_find_decoder(173)`). Vídeo de iPhone costuma ser HEVC |
| `sendWhatsAppAudio` com WAV | ✅ sai como nota de voz OGG/Opus |
| `sendReaction` e remoção (`""`) | ✅ os dois chegam como `messages.upsert` |
| `fetchAllGroups` | ✅ nas duas contas (34 e 3 grupos), com participantes |
| `inviteCode`, `revokeInviteCode`, `acceptInviteCode` (código e link) | ✅ entra pelo código novo e o `add` chega. ❌ Código vencido (410 `gone`) volta 500. Quem foi removido por um admin não volta pelo link logo depois (401, regra do WhatsApp), e também vira 500 |
| `leaveGroup` | ✅ sai e notifica as duas. ❌ Sair de um grupo em que já não está devolve `leave: true` |
| `updateProfileName` | ✅ o outro número vê o nome novo. ❌ O `fetchProfile` continua com o nome antigo (o `messages.upsert` já traz o novo) |
| `updateProfileStatus` | ❌ 500 (NPE no motor), **mas o recado muda** no servidor: o `IqSetAboutResponse` exige `id` numérico e o WhatsApp devolve o id em hexadecimal |
| `fetchProfile` (recado) | ❌ `status` sempre `null`, inclusive da própria conta: a consulta `w:mex` volta `result{}` vazio |
| `fetchProfilePictureUrl` | ✅ |
| `markMessageAsRead` | ✅ `READ` só na mensagem pedida |
| `whatsappNumbers` | ✅ com o 9 e formatado (`+55 (61) 9...`) resolvem para o mesmo JID; sem o DDI, `exists: false` |
| `sendPresence` (`composing`, `recording`) | ✅ aparece e volta a `available` no fim do `delay` (reteste do #48) |
| `settings/set` com a conexão aberta | ✅ `readMessages` ligado vale na hora (o `READ` sai em menos de 1 s) |

**Correções (mesmo dia, validadas ao vivo):** msgforge-api#68 (vCard recebido, convite recusado
vira 400, `leaveGroup` sem participação vira 404), cobalt-2#15 (troca de recado sem NPE, nome do
contato pelas mensagens), cobalt-2#16 (voto decifrado também por quem criou a enquete; recado lido
pelo protocolo `status` do usync, com o `contact`) e o PR seguinte da Msgforge (voto no
`messages.upsert` com `selectedOptions`, vídeo HEVC convertido para H.264 antes do envio).

Defeitos abertos desta rodada (antes das correções acima): na Msgforge, o `vcard` do contato recebido, o 500 do convite recusado, o
`leaveGroup` sem participação e o nome antigo no `fetchProfile`; no motor, o recado (troca e leitura) e o
vídeo HEVC; o voto de enquete vazio (ver se o motor já decifra). Não testados nesta rodada: proxy, código
de pareamento, `syncFullHistory: true` e `delete` de instância conectada.

## Cobertura das rotas (levantamento de 2026-11-16, atualizado em 2026-11-19)

As 42 rotas da API cruzadas com as rodadas acima, o `CLAUDE.md` e o ledger do motor
(`cobalt-2/docs/engine-review/STATUS.md`). ✅ = visto ao vivo; 🟡 = em parte; ⬜ = nenhum registro.
Ao validar um item, mude a marca aqui e registre a rodada.

| Grupo | ✅ validadas | 🟡 em parte | ⬜ sem registro |
|---|---|---|---|
| Instance | `create`, `connect` (QR), `logout`; `connectionState` e `fetchInstances` (indireto) | | `connect?number=` (pairing code), `delete` |
| Message | `sendText` (com `delay`), `sendWhatsAppAudio` (MP3, OGG, WAV, `encoding: false`), `sendLocation`, `sendReaction` (com remoção) | `sendMedia` (vídeo HEVC falha), `sendContact` (o recebido sai sem `vcard`), `sendPoll` (o voto chega vazio) | |
| Chat | `fetchProfilePictureUrl`, `updateProfileName`, `updateProfilePicture`, `markMessageAsRead`, `whatsappNumbers`, `sendPresence`, `findMessages`/`findChats` (só banco) | `fetchProfile` (recado sempre null; nome antigo depois da troca), `updateProfileStatus` (500, mas muda) | |
| Group | `create`, `fetchAllGroups`, `findGroupInfos`, `participants`, `updateParticipant` (4 ações), `updateGroupSubject`, `updateGroupDescription`, `updateGroupPicture`, `updateSetting`, `inviteCode`, `revokeInviteCode` | `acceptInviteCode` (recusa vira 500), `leaveGroup` (sem participação devolve sucesso) | grupo com participantes em LID |
| Settings/proxy/webhook | `webhook/set`/`find`, `settings/set`/`find` (com a conexão aberta) | | `proxy/set`/`find` e o proxy no `create`; `syncFullHistory: true` |

Pendências do motor que só se veem ao vivo: retry (`receipt type=retry`, 3.1), nome em "Aparelhos
conectados" num pareamento novo (3.11), vídeo que transcodifica (3.7) e `removeProfilePicture`
(sem rota na API).

## Próxima rodada (pendências, por risco)

**Estado em 2026-11-19:** os itens 3 a 6 e quase todo o 7 rodaram (ver "Rodada de 2026-11-19"). Faltam
o 1 (proxy), o 2 (código de pareamento), do 7 o `syncFullHistory: true` e o `delete` de instância
conectada, e o reteste dos defeitos daquela rodada depois de corrigidos. No item 3, quem foi removido
por um admin não volta pelo link logo depois (401 do WhatsApp): para testar o convite, crie o grupo
pelo B com o A e faça o A **sair** (`leaveGroup`) antes de aceitar o código.

Mesma stack do passo 1, duas instâncias (`A` e `B`, números de teste). Nos comandos,
`K=$AUTHENTICATION_API_KEY`, `NB` = número do B e `G` = um grupo de teste criado pelo A.

```bash
H=(-H "apikey: $K" -H "Content-Type: application/json"); U=http://localhost:8080
```

1. **Proxy por instância** (nunca testado). Precisa de um proxy de verdade (ex.: `ssh -D 1080` ou
   um squid local). Defina e reconecte (o proxy só vale numa conexão nova):
   ```bash
   curl -X POST $U/proxy/set/A "${H[@]}" -d '{"enabled":true,"host":"HOST","port":"1080","protocol":"socks5"}'
   curl $U/proxy/find/A -H "apikey: $K"          # sem a senha
   docker compose restart msgforge                # restaura a sessão pelo proxy
   ```
   Esperado: volta conectada e o tráfego passa pelo proxy (log do proxy). Repita com usuário e
   senha (`SECRETS_ENCRYPTION_KEY` no `.env`) e com um proxy desligado (deve falhar no connect,
   sem apagar a sessão). Por fim, `{"enabled":false}`.
2. **Código de pareamento:** instância nova, `curl "$U/instance/connect/C?number=55DDDNUMERO" -H "apikey: $K"`
   e digite o `pairingCode` no celular (Aparelhos conectados > Conectar com número). Anote o tempo
   até conectar. Confira também o nome em "Aparelhos conectados" (item 3.11 do motor).
3. **Convite e saída de grupo:**
   ```bash
   curl "$U/group/inviteCode/A?groupJid=$G" -H "apikey: $K"
   curl -X POST "$U/group/revokeInviteCode/A?groupJid=$G" -H "apikey: $K"   # o código antigo deixa de valer
   curl "$U/group/acceptInviteCode/B?inviteCode=CODIGO_NOVO" -H "apikey: $K"
   curl -X DELETE "$U/group/leaveGroup/B?groupJid=$G" -H "apikey: $K"
   curl "$U/group/fetchAllGroups/A?getParticipants=true" -H "apikey: $K"
   ```
   Esperado: o B entra pelo código novo (o antigo dá erro), sai, e os `group-participants.update`
   chegam nas duas instâncias. `fetchAllGroups` lista o grupo com os participantes certos.
4. **Localização, contato e enquete:**
   ```bash
   curl -X POST $U/message/sendLocation/A "${H[@]}" -d '{"number":"NB","latitude":-15.7939,"longitude":-47.8828,"name":"Esplanada","address":"Brasília"}'
   curl -X POST $U/message/sendContact/A "${H[@]}" -d '{"number":"NB","contact":[{"fullName":"Teste Um","phoneNumber":"5561999990001"}]}'
   curl -X POST $U/message/sendContact/A "${H[@]}" -d '{"number":"NB","contact":[{"fullName":"Um","phoneNumber":"5561999990001"},{"fullName":"Dois","phoneNumber":"5561999990002","organization":"Org","email":"dois@exemplo.com"}]}'
   curl -X POST $U/message/sendPoll/A "${H[@]}" -d '{"number":"NB","name":"Qual?","values":["Sim","Não","Talvez"],"selectableCount":1}'
   ```
   Esperado: o mapa abre no ponto; o cartão salva o número (e os dois contatos na versão com
   vários); a enquete aceita um voto só. Vote do B e anote como o voto chega no A: o
   `CobaltMessageMapper` não trata voto de enquete, então ele pode não sair ou sair como tipo
   desconhecido.
5. **Perfil da conta e de outro número:**
   ```bash
   curl -X POST $U/chat/updateProfileName/A "${H[@]}" -d '{"name":"Teste Msgforge"}'
   curl -X POST $U/chat/updateProfileStatus/A "${H[@]}" -d '{"status":"recado de teste"}'
   curl -X POST $U/chat/fetchProfilePictureUrl/A "${H[@]}" -d '{"number":"NB"}'
   ```
   Esperado: o B vê o nome e o recado novos (e o A recebe o `CONTACTS_UPDATE` do recado); a URL
   da foto abre. Com a foto escondida pela privacidade, `profilePictureUrl` null (não erro).
6. **Leitura, números e presença:**
   ```bash
   curl -X POST $U/chat/markMessageAsRead/A "${H[@]}" -d '{"readMessages":[{"remoteJid":"NB@s.whatsapp.net","fromMe":false,"id":"ID_DE_UMA_RECEBIDA"}]}'
   curl -X POST $U/chat/whatsappNumbers/A "${H[@]}" -d '{"numbers":["NB","NB_SEM_O_9","5561000000000"]}'
   curl -X POST $U/chat/sendPresence/A "${H[@]}" -d '{"number":"NB","presence":"composing","delay":3000}'
   curl -X POST $U/chat/sendPresence/A "${H[@]}" -d '{"number":"NB","presence":"recording","delay":3000}'
   ```
   Esperado: o tique azul só na mensagem pedida (com `readMessages` desligado); o número sem o 9
   resolve para o mesmo JID e o inexistente volta `exists: false`; "digitando..." e "gravando..."
   aparecem no B e somem no fim do `delay` (reteste do #48). Se houver uma conversa endereçada
   por LID, repita o `markMessageAsRead` nela.
7. **Mídia e configurações restantes:**
   - `sendMedia` com `mediatype: "document"` (um PDF, com `fileName`) e um vídeo que precise
     transcodificar (ex.: H.265 ou 1080p);
   - `sendWhatsAppAudio` com um WAV;
   - `sendReaction` com `"reaction": ""` (tira a reação);
   - `settings/set` com a conexão aberta (ex.: ligar `rejectCall` e ligar do B na hora, sem reconectar);
   - `syncFullHistory: true` num pareamento novo (`MESSAGES_SET` com o histórico completo);
   - `instance/delete` de uma instância conectada (a pasta da sessão em `/data/cobalt` some e o
     aparelho sai da lista do celular).

## Resultados (preencha e cole numa issue do fork ou numa conversa com o Claude)

Commit do fork testado: `________`

| # | Teste | Esperado | Resultado |
|---|---|---|---|
| 2 | Login após o scan | bem menos de 60 s | ___ s |
| 3 | Aviso de código de segurança no celular de B | não aparece | |
| 3 | Aviso de código de segurança no WhatsApp Web de B | não aparece | |
| 3 | 3 textos seguidos chegam | sim | |
| 4 | Envio depois da resposta do B sai como `enc type=msg` | sim (não `pkmsg`) | |
| 4 | Resposta do B aparece com o número (não LID) | sim | |
| 4 | Resposta pelo Web de B (aparelho vinculado) é decifrada | sim (sem `retry`) | |
| 4 | Status entregue / lida | avançam | |
| 5 | Imagem com legenda chega | sim | |
| 6 | Volta sem QR após restart | sim | |

Cada linha boa tira a pendência correspondente do item 5 de "Problemas conhecidos" do
`CLAUDE.md`. Com todas boas, marque a ADR 0007 como aceita. Cada linha ruim vira uma correção
no fork: guarde o log do trecho.
