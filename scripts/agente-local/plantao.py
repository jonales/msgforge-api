"""Plantão local: um agente com LLM local (Ollama) que trabalha junto com os agentes do Claude e
assume as tarefas marcadas como locais quando o Claude bate no limite de uso.

Roda como processo independente (não depende de nenhuma sessão do Claude estar viva):

    python3 -I scripts/agente-local/plantao.py            # fica em laço
    python3 -I scripts/agente-local/plantao.py --uma-vez  # uma passada e sai

Estado e fila em `.agente-local/` na raiz do repositório (fora do git):
    fila/*.json      tarefas pendentes        saida/<id>.md   resultado de cada tarefa
    feito/, falhou/  tarefas já processadas   estado.json     modo atual e última passada
    forcar           (arquivo vazio) liga o modo "limite" à mão

Regras de segurança:
- Só fala com o Ollama em localhost. Nunca faz push, merge, nem mexe em `main`.
- Comandos executáveis só da lista `COMANDOS` (sem shell).
- Código proposto pela LLM só é aplicado num worktree próprio (`local/<id>`), e o commit só
  acontece se a verificação passar. Quem decide se aproveita é o Claude ou o usuário, depois.
"""
import datetime as dt
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BASE = os.path.join(REPO, ".agente-local")
FILA, FEITO, FALHOU, SAIDA = (os.path.join(BASE, p) for p in ("fila", "feito", "falhou", "saida"))
LOG = os.path.join(BASE, "plantao.log")
ESTADO = os.path.join(BASE, "estado.json")
OLLAMA = "http://localhost:11434/api/chat"
MODELO_TEXTO = os.environ.get("PLANTAO_MODELO_TEXTO", "qwen2.5:7b")
MODELO_CODIGO = os.environ.get("PLANTAO_MODELO_CODIGO", "qwen2.5:7b")
INTERVALO = int(os.environ.get("PLANTAO_INTERVALO", "60"))
TRANSCRITOS = os.path.expanduser("~/.claude/projects/*msgforge*")
LIMITE = re.compile(r"hit your ([\w\- ]+?) limit · resets ([^()\"\\]+?)\s*\(")

JAVA_HOME = subprocess.run(["/usr/libexec/java_home", "-v", "25"], capture_output=True,
                           text=True).stdout.strip()
COMANDOS = {
    "mvn-test": ["mvn", "-q", "-B", "test"],
    "mvn-test-classe": ["mvn", "-q", "-B", "test", "-Dtest={classe}", "-Dsurefire.failIfNoSpecifiedTests=false"],
    "mvn-compile": ["mvn", "-q", "-B", "-DskipTests", "compile"],
    "git-status": ["git", "status", "--short"],
}

for p in (FILA, FEITO, FALHOU, SAIDA):
    os.makedirs(p, exist_ok=True)


def log(msg):
    linha = f"{dt.datetime.now():%Y-%m-%d %H:%M:%S} {msg}"
    print(linha, flush=True)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(linha + "\n")


def ler(caminho, limite=None):
    with open(caminho, encoding="utf-8", errors="replace") as f:
        texto = f.read()
    return texto[:limite] if limite else texto


# ---------------- detecção do limite do Claude ----------------
def _horario_de_volta(texto, quando):
    """'7:30pm' ou '3am' → próximo horário local depois do evento; senão evento + 5 h."""
    m = re.search(r"(\d{1,2})(?::(\d{2}))?\s*(am|pm)", texto, re.I)
    if not m:
        return quando + dt.timedelta(hours=5)
    h, mi = int(m.group(1)) % 12, int(m.group(2) or 0)
    if m.group(3).lower() == "pm":
        h += 12
    volta = quando.replace(hour=h, minute=mi, second=0, microsecond=0)
    return volta if volta > quando else volta + dt.timedelta(days=1)


def limite_ativo():
    """Devolve (ativo, motivo). Lê os transcritos recentes do Claude Code deste projeto."""
    if os.path.exists(os.path.join(BASE, "forcar")):
        return True, "forçado à mão (.agente-local/forcar)"
    agora = dt.datetime.now().astimezone()
    recente = time.time() - 6 * 3600
    ultimo, resposta = None, None
    for f in glob.glob(os.path.join(TRANSCRITOS, "**", "*.jsonl"), recursive=True):
        if os.path.getmtime(f) < recente:
            continue
        with open(f, encoding="utf-8", errors="replace") as arq:
            for linha in arq:
                if '"type":"assistant"' not in linha:
                    continue
                try:
                    d = json.loads(linha)
                    quando = dt.datetime.fromisoformat(d["timestamp"].replace("Z", "+00:00")).astimezone()
                except (ValueError, KeyError, TypeError):
                    continue
                if d.get("type") != "assistant":
                    continue
                if d.get("isApiErrorMessage") and d.get("error") == "rate_limit":
                    texto = json.dumps(d.get("message", {}).get("content"), ensure_ascii=False)
                    m = LIMITE.search(texto)
                    tipo, quando_volta = (m.group(1), m.group(2)) if m else ("uso", "")
                    if ultimo is None or quando > ultimo[0]:
                        ultimo = (quando, tipo, quando_volta)
                elif not d.get("isApiErrorMessage"):
                    resposta = quando if resposta is None or quando > resposta else resposta
    if not ultimo:
        return False, "sem limite registrado nas últimas 6 h"
    volta = _horario_de_volta(ultimo[2], ultimo[0])
    if resposta and resposta > ultimo[0] + dt.timedelta(minutes=2):
        return False, f"o Claude voltou a responder às {resposta:%H:%M} (limite das {ultimo[0]:%H:%M})"
    if agora < volta:
        return True, f"limite de {ultimo[1]} desde {ultimo[0]:%H:%M}, volta às {volta:%H:%M}"
    return False, f"último limite já passou (voltou às {volta:%H:%M})"


# ---------------- LLM ----------------
def perguntar(modelo, sistema, usuario, json_mode=False):
    corpo = {"model": modelo, "stream": False, "options": {"temperature": 0, "num_ctx": 16384},
             "messages": [{"role": "system", "content": sistema}, {"role": "user", "content": usuario}]}
    if json_mode:
        corpo["format"] = "json"
    req = urllib.request.Request(OLLAMA, data=json.dumps(corpo).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=1200) as r:
        return json.loads(r.read())["message"]["content"]


SISTEMA = ("Você é um engenheiro sênior revisando um projeto Java 25 + Spring Boot 4.1 (API de WhatsApp). "
           "Responda em português do Brasil, direto, em Markdown. Não invente arquivos, classes ou "
           "resultados: se a informação não está no texto recebido, diga que não está.")


def regras_do_projeto():
    return ler(os.path.join(REPO, "CLAUDE.md"), 12000)


# ---------------- execução segura ----------------
def rodar(nome, pasta, **params):
    cmd = [p.format(**params) for p in COMANDOS[nome]]
    env = dict(os.environ, JAVA_HOME=JAVA_HOME) if JAVA_HOME else dict(os.environ)
    r = subprocess.run(cmd, cwd=pasta, capture_output=True, text=True, env=env, timeout=3600)
    return r.returncode, (r.stdout + r.stderr)[-15000:]


def pasta_valida(pasta):
    real = os.path.realpath(pasta)
    if not real.startswith(os.path.realpath(REPO)):
        raise ValueError(f"pasta fora do repositório: {pasta}")
    if real == os.path.realpath(REPO):
        raise ValueError("tarefa no checkout principal não é permitida; use um worktree")
    return real


# ---------------- tipos de tarefa ----------------
def t_resumo(t):
    textos = [f"### {a}\n{ler(os.path.join(REPO, a), 8000)}" for a in t.get("arquivos", [])]
    return perguntar(MODELO_TEXTO, SISTEMA, f"{t['pergunta']}\n\n" + "\n\n".join(textos))


def t_testes(t):
    pasta = pasta_valida(t["pasta"])
    codigo, saida = rodar(t.get("comando", "mvn-test"), pasta, **t.get("params", {}))
    if codigo == 0:
        return f"Passou (`{t.get('comando', 'mvn-test')}`)."
    analise = perguntar(MODELO_TEXTO, SISTEMA,
                        "Os testes falharam. Liste cada falha (classe, teste, mensagem) e a causa provável, "
                        "citando só o que aparece na saída.\n\n```\n" + saida + "\n```")
    return f"Falhou (código {codigo}).\n\n{analise}\n\n<details><summary>saída</summary>\n\n```\n{saida[-5000:]}\n```\n</details>"


def t_revisao(t):
    pasta = pasta_valida(t["pasta"])
    base = t.get("base", "origin/main")
    diff = subprocess.run(["git", "diff", f"{base}...HEAD"], cwd=pasta, capture_output=True,
                          text=True).stdout
    partes = [diff[i:i + 12000] for i in range(0, min(len(diff), 60000), 12000)] or [""]
    notas = []
    for i, parte in enumerate(partes, 1):
        notas.append(perguntar(MODELO_TEXTO, SISTEMA,
                               "Regras do projeto:\n" + regras_do_projeto() +
                               f"\n\nRevise este trecho ({i}/{len(partes)}) do diff. Aponte só problemas "
                               "concretos (bug, regra do projeto violada, teste faltando), com arquivo e "
                               "linha. Se não houver, diga 'nada'.\n\n```diff\n" + parte + "\n```"))
    aviso = "" if len(diff) <= 60000 else f"\n\n_O diff tem {len(diff)} caracteres; revisei os primeiros 60000._"
    return "\n\n---\n\n".join(notas) + aviso


BLOCO = re.compile(r"ARQUIVO: (?P<arq>\S+)\n<<<<<<< BUSCA\n(?P<busca>.*?)\n=======\n(?P<troca>.*?)\n>>>>>>> TROCA", re.S)


def t_patch(t):
    """Pede à LLM blocos de busca/troca e aplica num worktree isolado; commita só se verificar."""
    origem = pasta_valida(t["pasta"])
    ref = subprocess.run(["git", "rev-parse", "HEAD"], cwd=origem, capture_output=True, text=True).stdout.strip()
    ramo = f"local/{t['id']}"
    destino = os.path.join(REPO, ".claude", "worktrees", f"local-{t['id']}")
    subprocess.run(["git", "worktree", "add", "-b", ramo, destino, ref], cwd=REPO, check=True,
                   capture_output=True)
    contexto = "\n\n".join(f"### {a}\n```\n{ler(os.path.join(destino, a), 12000)}\n```" for a in t["arquivos"])
    resposta = perguntar(MODELO_CODIGO, SISTEMA,
                         "Regras do projeto:\n" + regras_do_projeto()[:6000] +
                         f"\n\nTarefa: {t['instrucao']}\n\nArquivos:\n{contexto}\n\n"
                         "Responda SOMENTE com blocos neste formato exato, um por alteração:\n"
                         "ARQUIVO: caminho/relativo\n<<<<<<< BUSCA\n(trecho exato que existe hoje)\n=======\n"
                         "(trecho novo)\n>>>>>>> TROCA")
    aplicados, rejeitados = 0, []
    for b in BLOCO.finditer(resposta):
        alvo = os.path.join(destino, b.group("arq"))
        if not os.path.realpath(alvo).startswith(destino) or b.group("arq") not in t["arquivos"]:
            rejeitados.append(f"{b.group('arq')}: fora da lista de arquivos")
            continue
        atual = ler(alvo) if os.path.exists(alvo) else ""
        if atual.count(b.group("busca")) != 1:
            rejeitados.append(f"{b.group('arq')}: trecho de busca não encontrado exatamente uma vez")
            continue
        with open(alvo, "w", encoding="utf-8") as f:
            f.write(atual.replace(b.group("busca"), b.group("troca")))
        aplicados += 1
    relatorio = [f"Worktree: `{destino}` (branch `{ramo}`, a partir de `{ref[:9]}`)",
                 f"Blocos aplicados: {aplicados}; rejeitados: {len(rejeitados)}"] + [f"- {r}" for r in rejeitados]
    if aplicados == 0:
        relatorio.append("\nNada aplicado. Resposta da LLM:\n\n```\n" + resposta[:6000] + "\n```")
        return "\n".join(relatorio)
    codigo, saida = rodar(t.get("verificacao", "mvn-compile"), destino, **t.get("params", {}))
    if codigo == 0:
        subprocess.run(["git", "add", "-A"], cwd=destino, check=True)
        subprocess.run(["git", "commit", "-q", "-m", f"wip(local): {t['instrucao'][:60]}\n\n"
                        f"Gerado pelo plantão local ({MODELO_CODIGO}); revisar antes de aproveitar."],
                       cwd=destino, check=True)
        relatorio.append(f"\nVerificação `{t.get('verificacao', 'mvn-compile')}` passou; commit local em `{ramo}` (sem push).")
    else:
        relatorio.append(f"\nVerificação falhou (código {codigo}); alterações ficam no worktree, sem commit.\n\n```\n{saida[-4000:]}\n```")
    return "\n".join(relatorio)


TIPOS = {"resumo": t_resumo, "testes": t_testes, "revisao": t_revisao, "patch": t_patch}


# ---------------- handoff dos agentes do Claude ----------------
def colher_handoffs():
    """No modo limite, transforma os passos `[local]` dos HANDOFF.md dos worktrees em tarefas."""
    novas = 0
    for h in glob.glob(os.path.join(REPO, ".claude", "worktrees", "*", ".agente-local", "HANDOFF.md")):
        pasta = os.path.dirname(os.path.dirname(h))
        for n, linha in enumerate(ler(h).splitlines()):
            m = re.match(r"\s*- \[ \] \[local:(resumo|testes|revisao|patch)\]\s*(.+)", linha)
            if not m:
                continue
            ident = f"h-{os.path.basename(pasta)[-8:]}-{n}"
            if any(os.path.exists(os.path.join(d, ident + ".json")) for d in (FILA, FEITO, FALHOU)):
                continue
            tarefa = {"id": ident, "tipo": m.group(1), "pasta": pasta, "so_no_limite": True,
                      "origem": h}
            resto = m.group(2)
            arqs = re.findall(r"`([\w./-]+\.\w+)`", resto)
            if m.group(1) == "patch":
                tarefa.update(instrucao=resto, arquivos=arqs)
            elif m.group(1) == "resumo":
                tarefa.update(pergunta=resto, arquivos=arqs)
            with open(os.path.join(FILA, ident + ".json"), "w", encoding="utf-8") as f:
                json.dump(tarefa, f, ensure_ascii=False, indent=1)
            novas += 1
    if novas:
        log(f"handoff: {novas} tarefa(s) nova(s) colhida(s)")


# ---------------- laço ----------------
def gravar_estado(**kw):
    with open(ESTADO, "w", encoding="utf-8") as f:
        json.dump(dict(kw, atualizado=dt.datetime.now().isoformat(timespec="seconds")), f,
                  ensure_ascii=False, indent=1)


def passada():
    ativo, motivo = limite_ativo()
    if ativo:
        colher_handoffs()
    for caminho in sorted(glob.glob(os.path.join(FILA, "*.json"))):
        t = json.loads(ler(caminho))
        t.setdefault("id", os.path.basename(caminho)[:-5])
        if t.get("so_no_limite") and not ativo:
            continue
        gravar_estado(modo="limite" if ativo else "conjunto", motivo=motivo, tarefa=t["id"])
        log(f"tarefa {t['id']} ({t['tipo']}) começou")
        inicio = time.time()
        try:
            resultado = TIPOS[t["tipo"]](t)
            destino = FEITO
        except Exception as e:  # registra e segue para a próxima
            resultado, destino = f"Erro: {type(e).__name__}: {e}", FALHOU
        with open(os.path.join(SAIDA, t["id"] + ".md"), "w", encoding="utf-8") as f:
            f.write(f"# {t['id']} ({t['tipo']})\n\n{resultado}\n")
        shutil.move(caminho, os.path.join(destino, os.path.basename(caminho)))
        log(f"tarefa {t['id']} terminou em {time.time() - inicio:.0f} s → {os.path.basename(destino)}")
    gravar_estado(modo="limite" if ativo else "conjunto", motivo=motivo, tarefa=None)


if __name__ == "__main__":
    log(f"plantão iniciado (texto={MODELO_TEXTO}, código={MODELO_CODIGO})")
    while True:
        try:
            passada()
        except Exception as e:
            log(f"erro na passada: {type(e).__name__}: {e}")
        if "--uma-vez" in sys.argv:
            break
        time.sleep(INTERVALO)
