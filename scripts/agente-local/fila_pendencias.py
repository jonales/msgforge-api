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
PADRAO = {"testes": 60, "revisao": 140, "refinar": 110, "pergunta": 70, "auditoria": 15, "verify": 420}


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
    # 5. auditoria dos arquivos alterados nos últimos 3 dias
    alterados = git("log", "--since=3 days ago", "--name-only", "--format=", "main", "--", "src/main/java").split()
    for f in sorted(set(alterados)):
        if os.path.exists(os.path.join(REPO, f)):
            add("auditoria", dur["auditoria"], arquivo=f, foco="bugs")
    # 6. enchimento: rodadas da suíte completa do main (testes instáveis aparecem com volume)
    rodada = 0
    while total + dur["verify"] <= alvo and rodada < 200:
        rodada += 1
        add("testes", dur["verify"], ref="main", comando="mvn-verify")
    add("consolidar", 0)

    for t in tarefas:
        with open(os.path.join(FILA, t["id"] + ".json"), "w", encoding="utf-8") as f:
            json.dump(t, f, ensure_ascii=False)
    return len(tarefas), total / 3600, rodada


if __name__ == "__main__":
    qtd, h, rodadas = gerar(float(sys.argv[1]) if len(sys.argv) > 1 else 10.0)
    print(f"{qtd} tarefas, ~{h:.1f} h estimadas ({rodadas} rodadas de enchimento da suíte do main)")
