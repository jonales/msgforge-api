# Spike: Cobalt 0.0.10 como motor do WhatsApp

Projeto Maven **isolado**, fora do build da aplicação, que valida o Cobalt antes de construir o
`CobaltWhatsAppGateway`. As conclusões estão no
[ADR 0007](../../decisoes/0007-motor-whatsapp-cobalt.md).

Requisitos: JDK 25 e Maven 3.9.

## 1. Testes offline (já executados no spike)

```bash
cd docs/spikes/cobalt
mvn -q compile exec:java -Dexec.mainClass=spike.OfflineProbe
mvn -q compile exec:java -Dexec.mainClass=spike.ConnectFailureProbe
```

Resultado esperado: chaves geradas, ida e volta da serialização com `true/true`, cliente montado.
Sem acesso ao WhatsApp, `connect()` falha em menos de 1 s com `IOException`, sem travar.

## 2. Teste ao vivo pela própria API (recomendado, depois do gateway)

Com o `CobaltWhatsAppGateway` pronto, dá para testar direto na aplicação, sem o `LiveProbe`:

```bash
MSGFORGE_GATEWAY=cobalt mvn spring-boot:run          # Postgres + Redis locais
curl -X POST localhost:8080/instance/create -H "apikey: troque-esta-chave" \
  -H "Content-Type: application/json" -d '{"instanceName":"teste","qrcode":true}'
```

A resposta traz `qrcode.base64` (data URL). Cole no navegador e escaneie com o número de teste.
Depois: `GET /instance/connectionState/teste` deve dar `open`, e `POST /message/sendText/teste`
deve enviar. Reinicie a aplicação: ela deve reconectar sozinha, sem QR, porque a sessão fica no
Postgres. Os mesmos critérios da tabela abaixo valem.

## 3. Teste ao vivo com o LiveProbe (isolado da aplicação)

> ⚠️ Use um **número de teste**. Conexão não oficial pode levar ao banimento do número.

```bash
cd docs/spikes/cobalt
# QR no terminal; manda uma mensagem de teste para o 2º número
mvn -q compile exec:java -Dexec.mainClass=spike.LiveProbe -Dexec.args="qr 5561999999999"
# ou com pairing code (sem QR): número de teste + destino
mvn -q compile exec:java -Dexec.mainClass=spike.LiveProbe -Dexec.args="pair:5561988887777 5561999999999"
```

No celular do número de teste: WhatsApp → Aparelhos conectados → Conectar aparelho → escaneie
o QR (ou use "Conectar com número de telefone" no modo `pair:`).

### O que conferir (e me passar)

| # | Critério | No log |
|---|---|---|
| 1 | Login pelo QR ou pairing code | `LOGADO como ...@s.whatsapp.net` |
| 2 | Envio de texto | `ENVIADA id=...` e a mensagem chega no destino |
| 3 | Recebimento | mande uma mensagem para o número de teste → `RECEBIDA de ...` |
| 4 | Status de entrega/leitura | `STATUS <id> -> SERVER_ACK / DELIVERED / READ` |
| 5 | **Sessão persistida** | rode o mesmo comando de novo: `sessão encontrada: reconectando SEM QR` + `LOGADO` |
| 6 | Estabilidade | nenhum `ERRO em ...` ou `DESCONECTADO` inesperado em 3 minutos |

A sessão fica em `docs/spikes/cobalt/cobalt-session/`, que está no `.gitignore` porque contém as
credenciais do número. Apague essa pasta e desconecte o aparelho no celular quando terminar.

Se os critérios 1 a 5 passarem, o ADR 0007 vira "aceita" e o gateway é construído sobre o Cobalt.
Se falharem, o ADR descreve as alternativas.
