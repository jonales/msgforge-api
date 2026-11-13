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
AUTHENTICATION_API_KEY=troque-esta-chave
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
  -H "apikey: troque-esta-chave" -H "Content-Type: application/json" \
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
  -H "apikey: troque-esta-chave" -H "Content-Type: application/json" -d '{}'
```

## 5. Mídia

```bash
curl -X POST http://localhost:8080/message/sendMedia/teste \
  -H "apikey: troque-esta-chave" -H "Content-Type: application/json" \
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
