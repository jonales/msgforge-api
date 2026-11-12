# Validação ao vivo: motor Cobalt 0.1.0 (fork)

Roteiro para validar, com celulares de verdade, a troca do Cobalt 0.0.10 pelo fork
`jonales/Cobalt` (PR jonales/msgforge-api#12, branch `claude/elegant-faraday-6i4y7z`).
Não dá para rodar na nuvem: o proxy bloqueia o WhatsApp. Leva uns 30 minutos.

**O que decide a migração:** o teste 3 (aviso de código de segurança) e o teste 2 (tempo de
login). Os outros confirmam que nada regrediu.

## Antes de começar

- Dois celulares com WhatsApp: **A** (vira a instância do Msgforge) e **B** (recebe e responde).
  Use números de teste, não o seu número principal.
- Docker com Compose. A build instala o fork sozinha (`scripts/install-cobalt.sh`); não precisa
  de JDK nem de Maven na máquina.
- Uma pasta de anotações para os resultados (a tabela do fim deste arquivo).

## 1. Subir a stack

```bash
git clone https://github.com/jonales/msgforge-api.git   # ou: git fetch && git checkout
cd msgforge-api
git checkout claude/elegant-faraday-6i4y7z
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

Repita mais duas vezes (`teste 2`, `teste 3`). No celular **B**, abra a conversa.

Esperado: as 3 mensagens chegam e **não aparece** "Não foi possível confirmar seu código de
segurança". Se aparecer, anote em qual mensagem (a 1ª ou todas) e guarde o log desse trecho.

## 4. Receber e status

1. Responda do celular **B**.
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
- Para testar outro commit do motor, troque `COBALT_REF` em `scripts/install-cobalt.sh` e rode
  `docker compose up -d --build` de novo.

## Resultados (preencha e cole no PR #12 ou numa conversa com o Claude)

| # | Teste | Esperado | Resultado |
|---|---|---|---|
| 2 | Login após o scan | bem menos de 60 s | ___ s |
| 3 | Aviso de código de segurança no B | não aparece | |
| 3 | 3 textos seguidos chegam | sim | |
| 4 | Resposta do B aparece com o número (não LID) | sim | |
| 4 | Status entregue / lida | avançam | |
| 5 | Imagem com legenda chega | sim | |
| 6 | Volta sem QR após restart | sim | |

Com os testes 2 e 3 bons, a migração está validada: tire o item 5 de "Problemas conhecidos" do
`CLAUDE.md` e marque a ADR 0007 como aceita.
