"""Gera uma fila de pendências para o plantão cobrir N horas, com o tempo médio medido de cada tipo.

Ordem (do mais útil ao enchimento):
1. passos [local:...] dos HANDOFF.md dos worktrees;
2. suíte completa e revisão de cada branch local ainda não juntada ao main;
3. suíte completa do main;
4. refinamento de cada item de `.agente-local/backlog.md` ainda não refinado nos últimos 2 dias;
5. auditoria de bugs dos arquivos de produção alterados nos últimos 3 dias;
6. enchimento: rodadas da suíte completa do main, para pegar teste instável;
7. relatório consolidado.

Uso: python3 -I scripts/agente-local/fila_pendencias.py [horas]   (padrão 10)
"""
import datetime as dt
import glob
import json
import os
import re
import subprocess
import sys
import time

AQUI = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(subprocess.run(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
                                      cwd=AQUI, capture_output=True, text=True).stdout.strip())
BASE = os.path.join(REPO, ".agente-local")
FILA, SAIDA = os.path.join(BASE, "fila"), os.path.join(BASE, "saida")
PADRAO = {"testes": 60, "revisao": 140, "refinar": 110, "pergunta": 70, "auditoria": 15, "verify": 150}


def git(*args):
    return subprocess.run(["git", *args], cwd=REPO, capture_output=True, text=True).stdout


def duracoes():
    """Mediana em segundos de cada tipo, medida no plantao.log (padrão quando não há medida)."""
    tipo, medidas = {}, {}
    log = os.path.join(BASE, "plantao.log")
    if os.path.exists(log):
        for linha in open(log, encoding="utf-8", errors="replace"):
            m = re.search(r"tarefa (\S+) \((\w+)\) começou", linha)
            if m:
                tipo[m.group(1)] = m.group(2)
                continue
            m = re.search(r"tarefa (\S+) terminou em (\d+) s", linha)
            if m and m.group(1) in tipo:
                medidas.setdefault(tipo[m.group(1)], []).append(int(m.group(2)))
    out = dict(PADRAO)
    for t, v in medidas.items():
        if len(v) >= 3:
            out[t] = sorted(v)[len(v) // 2]
    return out


REVISAO = ("Revise o levantamento anexo conferindo contra o código do projeto: (1) cada arquivo, classe e método "
           "citado existe? marque ✓ ou ✗; (2) o que o levantamento esqueceu (migration, permissão, "
           "messages.properties, docs, teste); (3) a ordem de implementação em passos pequenos; (4) perguntas que "
           "só o usuário responde. Responda em tópicos curtos.")
PLANO_DE_TESTES = ("A partir do levantamento anexo, escreva o plano de testes: unitários (classe a criar ou estender, "
                   "casos com entrada e saída esperada), *IT (HTTP → serviço → banco → evento), casos de erro e de "
                   "permissão, e o que só dá para validar ao vivo.")
TABELA = ('Responda SÓ com uma tabela Markdown e, abaixo, no máximo 5 linhas de observações. Use apenas o que está '
          'nos arquivos anexos; o que não der para afirmar, marque "?" em vez de supor.')


def tarefas_de_dados():
    """Perguntas que devolvem tabelas (contrato x código, eventos, variáveis, permissões, cobertura)."""
    j = "src/main/java/dev/msgforge/"
    out = []
    for grupo, ctrl in (("instance", "instance/InstanceController.java"), ("message", "message/MessageController.java"),
                        ("chat", "chat/ChatController.java"), ("group", "group/GroupController.java"),
                        ("webhook", "webhook/WebhookController.java")):
        out.append((f"Matriz do contrato, grupo {grupo}: para cada rota de /{grupo}/ em docs/COMPATIBILIDADE.md, colunas: "
                    "Rota | Método | Status no contrato | Existe no controller anexo? (✓/✗ e método) | Campos aceitos | "
                    f"Diferença. Rotas de outros grupos não entram. {TABELA}", ["docs/COMPATIBILIDADE.md", j + ctrl]))
    out.append((f"Matriz de eventos: para cada evento de docs/eventos.md, colunas: Evento | EventType | Montado em "
                f"EventPayloads? | Campos do data. {TABELA}",
                ["docs/eventos.md", j + "event/EventType.java", j + "event/EventPayloads.java"]))
    out.append((f"Variáveis de ambiente: para cada ${{...}} do application.yml, colunas: Variável | Padrão | Documentada "
                f"em docs/configuracao.md? {TABELA}", ["src/main/resources/application.yml", "docs/configuracao.md"]))
    for f in sorted(glob.glob(os.path.join(REPO, "src", "main", "java", "dev", "msgforge", "web", "*Controller.java"))):
        out.append((f"Permissões por rota do Manager em {os.path.basename(f)}: colunas: Rota | Permissão exigida "
                     f"(ManagerAccess.require, admin da plataforma ou só login) | Resposta sem permissão. {TABELA}",
                     [os.path.relpath(f, REPO), j + "access/Permission.java"]))
    for ctrl, it in (("chat/ChatController.java", "ChatIT"), ("group/GroupController.java", "GroupIT"),
                     ("instance/InstanceController.java", "InstanceFlowIT")):
        caminho = f"src/test/java/dev/msgforge/{it}.java"
        if os.path.exists(os.path.join(REPO, caminho)):
            out.append((f"Cobertura de testes: para cada rota do controller anexo, colunas: Rota | Testada no IT anexo? "
                        f"(✓/✗ e teste) | Casos cobertos | Caso que falta. {TABELA}", [j + ctrl, caminho]))
    return out


def recentes_anexos():
    """Arquivos já anexados a perguntas feitas nos últimos 2 dias (feito/ e fila/)."""
    vistos = set()
    for pasta in ("feito", "fila"):
        for f in glob.glob(os.path.join(BASE, pasta, "*.json")):
            if time.time() - os.path.getmtime(f) > 2 * 86400:
                continue
            try:
                t = json.load(open(f, encoding="utf-8"))
            except (ValueError, OSError):
                continue
            if t.get("tipo") == "pergunta":
                vistos.update(t.get("arquivos", []))
    return vistos


def gerar(horas=10.0):
    os.makedirs(FILA, exist_ok=True)
    alvo, dur = horas * 3600, duracoes()
    carimbo = dt.datetime.now().strftime("%m%d%H%M")
    total, n, tarefas = 0, [0], []

    def add(tipo, custo, **campos):
        nonlocal total
        n[0] += 1
        ident = f"g{carimbo}-{n[0]:04d}-{tipo}"
        tarefas.append({"id": ident, "tipo": tipo, **campos})
        total += custo

    # 1. passos locais dos HANDOFF
    for h in glob.glob(os.path.join(REPO, ".claude", "worktrees", "*", ".agente-local", "HANDOFF.md")):
        pasta = os.path.dirname(os.path.dirname(h))
        for linha in open(h, encoding="utf-8", errors="replace"):
            m = re.match(r"\s*- \[ \] \[local:(testes|revisao)\]", linha)
            if m and m.group(1) == "testes":
                add("testes", dur["verify"], pasta=pasta, comando="mvn-verify")
            elif m:
                add("revisao", dur["revisao"], pasta=pasta, base="main")
    # 2. branches locais ainda fora do main
    for b in git("branch", "--format=%(refname:short)", "--no-merged", "main").split():
        if b.startswith(("local/", "worktree-agent-")):
            continue
        add("testes", dur["verify"], ref=b, comando="mvn-verify")
        add("revisao", dur["revisao"], ref=b, base="main")
    # 3. suíte do main
    add("testes", dur["verify"], ref="main", comando="mvn-verify")
    # 4. backlog ainda não refinado nos últimos 2 dias
    backlog = os.path.join(BASE, "backlog.md")
    recentes = ""
    for f in glob.glob(os.path.join(SAIDA, "*.md")):
        if time.time() - os.path.getmtime(f) < 2 * 86400:
            recentes += open(f, encoding="utf-8", errors="replace").read(400)
    if os.path.exists(backlog):
        for linha in open(backlog, encoding="utf-8"):
            item = linha.strip()[2:].strip() if linha.strip().startswith("- ") else ""
            if item and item[:60] not in recentes:
                add("refinar", dur["refinar"], texto=item)
    # 5. arquivos alterados nos últimos 3 dias: testes que faltam (a auditoria de "bugs" do modelo local
    #    não teve nenhum achado confirmado; a de testes aponta casos concretos)
    alterados = git("log", "--since=3 days ago", "--name-only", "--format=", "main", "--", "src/main/java").split()
    for f in sorted(set(alterados)):
        if os.path.exists(os.path.join(REPO, f)):
            add("auditoria", dur["auditoria"], arquivo=f, foco="testes")
    # 6. tarefas que trazem dados (tabelas para usar direto), se não saíram nos últimos 2 dias
    for texto, arquivos in tarefas_de_dados():
        if texto[:60] not in recentes:
            add("pergunta", dur["pergunta"], texto=texto, arquivos=arquivos)
    # 7. levantamentos recentes ainda sem revisão e sem plano de testes
    #    (o mesmo item do backlog pode ter sido refinado várias vezes: vale só o mais recente; até 20 por fila)
    anexados, mais_recente = recentes_anexos(), {}
    for saida in sorted(glob.glob(os.path.join(SAIDA, "*refinar*.md")), key=os.path.getmtime):
        if time.time() - os.path.getmtime(saida) > 2 * 86400:
            continue
        pedido = next((l for l in open(saida, encoding="utf-8", errors="replace") if l.startswith("> ")), saida)
        mais_recente[pedido[:120]] = os.path.relpath(saida, REPO)
    pendentes = [rel for rel in mais_recente.values() if rel not in anexados]
    for rel in pendentes[-20:]:
        add("pergunta", dur["pergunta"], texto=REVISAO, arquivos=[rel])
        add("pergunta", dur["pergunta"], texto=PLANO_DE_TESTES, arquivos=[rel])
    # 8. enchimento: poucas rodadas da suíte do main e depois os *IT um a um (mais barato e aponta a classe)
    rodada = 0
    while total + dur["verify"] <= alvo and rodada < 6:
        rodada += 1
        add("testes", dur["verify"], ref="main", comando="mvn-verify")
    its = sorted(os.path.basename(f)[:-5] for f in glob.glob(os.path.join(REPO, "src", "test", "java", "**", "*IT.java"), recursive=True))
    for _ in range(3):
        for it in its:
            if total + dur["testes"] > alvo:
                break
            add("testes", dur["testes"], ref="main", comando="mvn-it-classe", params={"classe": it})
    add("consolidar", 0)

    for t in tarefas:
        with open(os.path.join(FILA, t["id"] + ".json"), "w", encoding="utf-8") as f:
            json.dump(t, f, ensure_ascii=False)
    return len(tarefas), total / 3600, rodada


if __name__ == "__main__":
    qtd, h, rodadas = gerar(float(sys.argv[1]) if len(sys.argv) > 1 else 10.0)
    print(f"{qtd} tarefas, ~{h:.1f} h estimadas ({rodadas} rodadas de enchimento da suíte do main)")
