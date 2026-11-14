"""Plantão local: agente com LLM local (Ollama) que trabalha junto com os agentes do Claude, refina
tarefas antes de chegarem ao Claude e assume os passos marcados como locais quando o Claude bate no
limite de uso. Roda como processo independente:

    python3 -I scripts/agente-local/plantao.py            # fica em laço
    python3 -I scripts/agente-local/plantao.py --uma-vez  # uma passada e sai

Tarefas entram pela CLI `tarefa.py` (veja o README). Estado em `.agente-local/` na raiz do
repositório principal (fora do git), compartilhado por todos os worktrees.

Segurança: rede só para o Ollama local; nunca faz push, merge nem mexe no checkout principal
(testes e patches rodam em worktrees próprios); comandos só da lista COMANDOS, sem shell.
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
import urllib.error
import urllib.request


def _raiz_do_repo():
    comum = subprocess.run(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
                           cwd=os.path.dirname(os.path.abspath(__file__)), capture_output=True,
                           text=True).stdout.strip()
    return os.path.dirname(comum)


REPO = os.environ.get("PLANTAO_REPO") or _raiz_do_repo()
BASE = os.path.join(REPO, ".agente-local")
FILA, FEITO, FALHOU, SAIDA = (os.path.join(BASE, p) for p in ("fila", "feito", "falhou", "saida"))
LOG, ESTADO, LICOES = (os.path.join(BASE, p) for p in ("plantao.log", "estado.json", "licoes.md"))
WORKTREES = os.path.join(REPO, ".claude", "worktrees")
OLLAMA = "http://localhost:11434"
INTERVALO = int(os.environ.get("PLANTAO_INTERVALO", "20"))
MAX_TENTATIVAS = 2
TRANSCRITOS = os.path.expanduser("~/.claude/projects/*msgforge*")
LIMITE = re.compile(r"hit your ([\w\- ]+?) limit · resets ([^()\"\\]+?)\s*\(")

JAVA_HOME = subprocess.run(["/usr/libexec/java_home", "-v", "25"], capture_output=True,
                           text=True).stdout.strip()
COMANDOS = {
    "mvn-test": ["mvn", "-q", "-B", "test"],
    "mvn-test-classe": ["mvn", "-q", "-B", "test", "-Dtest={classe}", "-Dsurefire.failIfNoSpecifiedTests=false"],
    "mvn-it-classe": ["mvn", "-q", "-B", "verify", "-Dtest=none", "-Dsurefire.failIfNoSpecifiedTests=false",
                      "-Dit.test={classe}"],
    "mvn-compile": ["mvn", "-q", "-B", "-DskipTests", "test-compile"],
}

for p in (FILA, FEITO, FALHOU, SAIDA, WORKTREES):
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


def git(*args, cwd=REPO, check=True):
    r = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)}: {r.stderr.strip()[:300]}")
    return r.stdout


# ---------------- modelos ----------------
def modelos_instalados():
    try:
        with urllib.request.urlopen(OLLAMA + "/api/tags", timeout=10) as r:
            return {m["name"] for m in json.loads(r.read())["models"]}
    except Exception:
        return set()


def escolher(preferido, reserva):
    instalados = modelos_instalados()
    return preferido if preferido in instalados else reserva


MODELO_TEXTO = os.environ.get("PLANTAO_MODELO_TEXTO", "qwen2.5:7b")
MODELO_CODIGO = os.environ.get("PLANTAO_MODELO_CODIGO") or escolher("qwen2.5-coder:14b", MODELO_TEXTO)


def destravar_ollama():
    """O runner do Ollama às vezes fica preso em 'Stopping...'. Encerra só o runner (o app sobe outro)."""
    ps = subprocess.run(["ollama", "ps"], capture_output=True, text=True).stdout
    if "Stopping" in ps:
        subprocess.run(["pkill", "-f", "Ollama.app/Contents/Resources/llama-server"])
        log("ollama: runner travado em 'Stopping...' encerrado")
        time.sleep(5)
        return True
    return False


def perguntar(modelo, sistema, usuario, json_mode=False, ctx=16384):
    corpo = {"model": modelo, "stream": False, "keep_alive": "30m",
             "options": {"temperature": 0, "num_ctx": ctx, "num_predict": 4096, "repeat_penalty": 1.15},  # sem teto e penalidade, modelo pequeno pode entrar em repetição sem fim
             "messages": [{"role": "system", "content": sistema}, {"role": "user", "content": usuario}]}
    if json_mode:
        corpo["format"] = "json"
    for tentativa in (1, 2):
        req = urllib.request.Request(OLLAMA + "/api/chat", data=json.dumps(corpo).encode(),
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=900) as r:
                return json.loads(r.read())["message"]["content"]
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            if tentativa == 2 or not destravar_ollama():
                raise RuntimeError(f"ollama: {e}") from e


# ---------------- contexto do projeto ----------------
def licoes():
    return ler(LICOES, 5000) if os.path.exists(LICOES) else ""


def sistema(papel):
    texto = (f"Você é {papel} num projeto Java 25 + Spring Boot 4.1 (API REST de WhatsApp, Msgforge). "
             "Responda em português do Brasil, direto, em Markdown. Nunca invente arquivos, classes, "
             "métodos ou resultados: use só o que está no texto recebido e, se faltar informação, diga o "
             "que falta.")
    l = licoes()
    return texto + ("\n\nLIÇÕES APRENDIDAS NESTE PROJETO (siga sempre):\n" + l if l else "")


def regras(limite=9000):
    return ler(os.path.join(REPO, "CLAUDE.md"), limite)


def grafo(pergunta):
    """Contexto do código pelo grafo do graphify (sem LLM). Vazio se não houver grafo."""
    grafo_json = os.path.join(REPO, "graphify-out", "graph.json")
    if not os.path.exists(grafo_json) or not shutil.which("graphify"):
        return ""
    # Grafo mais velho que o último commit do main não conhece o código novo: atualiza (só AST, sem LLM)
    ultimo_commit = int(git("log", "-1", "--format=%ct", "main").strip() or 0)
    if os.path.getmtime(grafo_json) < ultimo_commit:
        subprocess.run(["graphify", "update", "."], cwd=REPO, capture_output=True, timeout=600)
        log("graphify: grafo atualizado (estava mais velho que o main)")
    try:
        r = subprocess.run(["graphify", "query", pergunta], cwd=REPO, capture_output=True, text=True, timeout=90)
        return r.stdout[:7000]
    except subprocess.TimeoutExpired:
        return ""


def codigo_citado(texto, limite=2):
    """Código-fonte das classes citadas pelo nome (ex.: EventDispatcher), do main."""
    nomes = list(dict.fromkeys(re.findall(r"\b([A-Z][a-z]+(?:[A-Z][a-z0-9]*)+)\b", texto)))
    partes = []
    for nome in nomes:
        achados = [f for f in git("ls-files", f"src/main/java/*/{nome}.java").split() if f]
        if achados:
            partes.append(f"### {achados[0]}\n```java\n{ler(os.path.join(REPO, achados[0]), 14000)}\n```")
        if len(partes) >= limite:
            break
    return "\n\n".join(partes)


def adrs_citadas(texto):
    """Texto das ADRs citadas no pedido ("ADR 0010", "ADRs 0010 e 0011"), de docs/decisoes/."""
    partes = []
    for bloco in re.findall(r"ADRs?\s+((?:\d{4}(?:\s*(?:,|e|a)\s*)?)+)", texto):
        for num in re.findall(r"\d{4}", bloco):
            for f in glob.glob(os.path.join(REPO, "docs", "decisoes", f"{num}-*.md")):
                partes.append(f"### {os.path.relpath(f, REPO)}\n{ler(f, 9000)}")
    return "\n\n".join(list(dict.fromkeys(partes))[:2])


CAMINHO = re.compile(r"`?((?:src|docs|scripts)/[\w./-]+\.\w+)`?")


def conferir_caminhos(texto):
    """Marca cada caminho citado como existente (✓) ou não (✗), contra o checkout principal."""
    vistos = {}
    for c in set(CAMINHO.findall(texto)):
        vistos[c] = os.path.exists(os.path.join(REPO, c))
    if not vistos:
        return ""
    linhas = [f"- {'✓' if ok else '✗ NÃO EXISTE'} `{c}`" for c, ok in sorted(vistos.items())]
    return "\n\n## Conferência dos caminhos citados\n" + "\n".join(linhas)


# ---------------- worktrees e comandos ----------------
def rodar(nome, pasta, **params):
    cmd = [p.format(**params) for p in COMANDOS[nome]]
    env = dict(os.environ, JAVA_HOME=JAVA_HOME) if JAVA_HOME else dict(os.environ)
    r = subprocess.run(cmd, cwd=pasta, capture_output=True, text=True, env=env, timeout=3600)
    return r.returncode, (r.stdout + r.stderr)[-15000:]


def worktree_novo(t, ramo=None):
    """Worktree descartável a partir de `ref` (padrão main) ou do HEAD de `pasta`."""
    ref = git("rev-parse", "HEAD", cwd=pasta_valida(t["pasta"])).strip() if t.get("pasta") else \
        git("rev-parse", t.get("ref", "main")).strip()
    destino = os.path.join(WORKTREES, f"local-{t['id']}")
    if ramo:
        git("worktree", "add", "-b", ramo, destino, ref)
    else:
        git("worktree", "add", "--detach", destino, ref)
    return destino, ref


def remover_worktree(destino):
    git("worktree", "remove", "--force", destino, check=False)


def pasta_valida(pasta):
    real = os.path.realpath(pasta)
    if not real.startswith(os.path.realpath(WORKTREES) + os.sep):
        raise ValueError(f"a pasta precisa ser um worktree em .claude/worktrees: {pasta}")
    return real


def preparar_cobalt(pasta):
    """O motor fica fora do Maven Central; o script só instala se faltar no ~/.m2."""
    script = os.path.join(pasta, "scripts", "install-cobalt.sh")
    if os.path.exists(script):
        subprocess.run(["bash", script], cwd=pasta, capture_output=True, timeout=1800)


# ---------------- tipos de tarefa ----------------
def t_pergunta(t):
    arquivos = "\n\n".join(f"### {a}\n```\n{ler(os.path.join(REPO, a), 8000)}\n```" for a in t.get("arquivos", []))
    contexto = grafo(t["texto"])
    codigo = codigo_citado(t["texto"])
    resposta = perguntar(MODELO_CODIGO, sistema("um engenheiro sênior que conhece o código"),
                         f"Pergunta: {t['texto']}\n\nCÓDIGO DAS CLASSES CITADAS (fonte da verdade; responda a "
                         f"partir dele):\n{codigo}\n\nGRAFO DO CÓDIGO (trechos relevantes):\n{contexto}\n\n{arquivos}",
                         ctx=24576)
    return resposta + conferir_caminhos(resposta)


def t_refinar(t):
    """Transforma um pedido cru numa especificação pronta para o Claude executar sem retrabalho."""
    contexto = grafo(t["texto"])
    codigo = codigo_citado(contexto + " " + t["texto"])
    pedido = f"""Pedido do usuário (cru): {t['texto']}

REGRAS DO PROJETO (CLAUDE.md):
{regras()}

GRAFO DO CÓDIGO (trechos relevantes, gerado por ferramenta, confiável):
{contexto}

CÓDIGO DAS CLASSES MAIS RELEVANTES:
{codigo}

DECISÕES DE ARQUITETURA CITADAS (seguir à risca):
{adrs_citadas(t['texto'])}

Escreva uma especificação curta com EXATAMENTE estas seções:
## Objetivo
(1 a 3 frases: o que muda para quem usa)
## Contexto no código
(arquivos e classes envolvidos, SÓ os que aparecem no grafo acima, com o caminho)
## Critérios de aceite
(lista verificável: comportamento, testes que precisam existir, docs a atualizar)
## Riscos e regras do projeto que se aplicam
(cite a regra do CLAUDE.md que pesa aqui)
## Passos
(lista "- [ ]"; marque [local:testes], [local:revisao] ou [local:patch] nos passos pequenos e
mecânicos que um modelo local resolve; o resto fica para o Claude, sem marca)
## Perguntas em aberto
(o que o usuário precisa decidir; "nenhuma" se não houver)"""
    resposta = perguntar(MODELO_CODIGO, sistema("um analista técnico que prepara tarefas para outro engenheiro"),
                         pedido, ctx=32768)
    return resposta + conferir_caminhos(resposta)


def t_resumo(t):
    textos = "\n\n".join(f"### {a}\n{ler(os.path.join(REPO, a), 9000)}" for a in t.get("arquivos", []))
    return perguntar(MODELO_TEXTO, sistema("um engenheiro sênior"), f"{t['texto']}\n\n{textos}")


def t_revisao(t):
    base = t.get("base", "origin/main")
    if t.get("pasta"):
        diff = git("diff", f"{base}...HEAD", cwd=pasta_valida(t["pasta"]))
    else:
        diff = git("diff", f"{base}...{t['ref']}")
    if not diff.strip():
        return "Diff vazio: nada a revisar."
    partes = [diff[i:i + 12000] for i in range(0, min(len(diff), 72000), 12000)]
    notas = []
    for i, parte in enumerate(partes, 1):
        notas.append(f"### Trecho {i}/{len(partes)}\n" + perguntar(
            MODELO_CODIGO, sistema("um revisor de código rigoroso"),
            "REGRAS DO PROJETO:\n" + regras(7000) +
            f"\n\nRevise este trecho do diff. Aponte só problemas concretos (bug, regra do projeto "
            "violada, teste faltando), com arquivo e linha, e diga a gravidade (alta/média/baixa). Se não "
            "houver nada, responda só 'nada'.\n\n```diff\n" + parte + "\n```"))
    aviso = "" if len(diff) <= 72000 else f"\n\n_O diff tem {len(diff)} caracteres; revisei os primeiros 72000._"
    return "\n\n".join(notas) + aviso


def t_testes(t):
    temporario = not t.get("pasta")
    pasta, ref = (worktree_novo(t) if temporario else (pasta_valida(t["pasta"]), "HEAD"))
    try:
        preparar_cobalt(pasta)
        comando = t.get("comando", "mvn-test")
        codigo, saida = rodar(comando, pasta, **t.get("params", {}))
        total = contar_testes(pasta)
    finally:
        if temporario:
            remover_worktree(pasta)
    if codigo == 0:
        return f"Passou (`{comando}` em `{ref[:9]}`): {total}."
    analise = perguntar(MODELO_CODIGO, sistema("um engenheiro que investiga testes que falham"),
                        "Os testes falharam. Liste cada falha (classe, teste, mensagem) e a causa provável, "
                        "citando só o que aparece na saída.\n\n```\n" + saida + "\n```")
    return (f"Falhou (código {codigo}, `{comando}` em `{ref[:9]}`).\n\n{analise}\n\n"
            f"<details><summary>saída</summary>\n\n```\n{saida[-5000:]}\n```\n</details>")


def contar_testes(pasta):
    t = e = f = p = 0
    for x in glob.glob(os.path.join(pasta, "target", "*-reports", "TEST-*.xml")):
        m = re.search(r'<testsuite[^>]*?tests="(\d+)"[^>]*?errors="(\d+)"[^>]*?skipped="(\d+)"[^>]*?failures="(\d+)"', ler(x, 3000))
        if m:
            t, e, p, f = t + int(m.group(1)), e + int(m.group(2)), p + int(m.group(3)), f + int(m.group(4))
    return f"{t} testes, {f} falhas, {e} erros, {p} pulados"


BLOCO = re.compile(r"ARQUIVO: (?P<arq>\S+)\s*\n<<<<<<< BUSCA\n(?P<busca>.*?)\n=======\n(?P<troca>.*?)\n>>>>>>> TROCA", re.S)


def t_patch(t):
    """Blocos de busca/troca num worktree novo `local/<id>`; commit local só se a verificação passar."""
    ramo = f"local/{t['id']}"
    destino, ref = worktree_novo(t, ramo=ramo)
    contexto = "\n\n".join(f"### {a}\n```\n{ler(os.path.join(destino, a), 14000)}\n```" for a in t["arquivos"])
    resposta = perguntar(MODELO_CODIGO, sistema("um engenheiro Java cuidadoso"),
                         "REGRAS DO PROJETO:\n" + regras(6000) +
                         f"\n\nTarefa: {t['texto']}\n\nArquivos:\n{contexto}\n\n"
                         "Responda SOMENTE com blocos neste formato exato, um por alteração (o trecho de BUSCA "
                         "precisa existir igual, com a mesma indentação, e aparecer uma vez só):\n"
                         "ARQUIVO: caminho/relativo\n<<<<<<< BUSCA\n(trecho exato de hoje)\n=======\n"
                         "(trecho novo)\n>>>>>>> TROCA", ctx=24576)
    aplicados, rejeitados = 0, []
    for b in BLOCO.finditer(resposta):
        arq = b.group("arq")
        alvo = os.path.realpath(os.path.join(destino, arq))
        if arq not in t["arquivos"] or not alvo.startswith(os.path.realpath(destino) + os.sep):
            rejeitados.append(f"`{arq}`: fora da lista de arquivos")
            continue
        atual = ler(alvo) if os.path.exists(alvo) else ""
        if atual.count(b.group("busca")) != 1:
            rejeitados.append(f"`{arq}`: trecho de busca não aparece exatamente uma vez")
            continue
        with open(alvo, "w", encoding="utf-8") as f:
            f.write(atual.replace(b.group("busca"), b.group("troca")))
        aplicados += 1
    rel = [f"Worktree `{destino}`, branch `{ramo}` (a partir de `{ref[:9]}`), modelo `{MODELO_CODIGO}`.",
           f"Blocos aplicados: {aplicados}; rejeitados: {len(rejeitados)}"] + [f"- {r}" for r in rejeitados]
    if aplicados == 0:
        remover_worktree(destino)
        git("branch", "-D", ramo, check=False)
        return "\n".join(rel + ["\nNada aplicado (worktree descartado). Resposta do modelo:\n\n```\n" + resposta[:6000] + "\n```"])
    preparar_cobalt(destino)
    verificacao = t.get("verificacao", "mvn-compile")
    codigo, saida = rodar(verificacao, destino, **t.get("params", {}))
    diff = git("diff", cwd=destino)
    if codigo == 0:
        git("add", "-A", cwd=destino)
        git("commit", "-q", "-m", f"wip(local): {t['texto'][:60]}\n\nGerado pelo plantão local "
            f"({MODELO_CODIGO}); revisar antes de aproveitar.", cwd=destino)
        rel.append(f"\nVerificação `{verificacao}` passou; commit local em `{ramo}` (sem push).")
    else:
        rel.append(f"\nVerificação `{verificacao}` falhou (código {codigo}); sem commit.\n\n```\n{saida[-4000:]}\n```")
    return "\n".join(rel) + f"\n\n```diff\n{diff[:12000]}\n```"


TIPOS = {"pergunta": t_pergunta, "refinar": t_refinar, "resumo": t_resumo, "revisao": t_revisao,
         "testes": t_testes, "patch": t_patch}


# ---------------- limite do Claude e passagem ----------------
def _horario_de_volta(texto, quando):
    m = re.search(r"(\d{1,2})(?::(\d{2}))?\s*(am|pm)", texto, re.I)
    if not m:
        return quando + dt.timedelta(hours=5)
    h, mi = int(m.group(1)) % 12, int(m.group(2) or 0)
    if m.group(3).lower() == "pm":
        h += 12
    volta = quando.replace(hour=h, minute=mi, second=0, microsecond=0)
    return volta if volta > quando else volta + dt.timedelta(days=1)


def limite_ativo():
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
                    m = LIMITE.search(json.dumps(d.get("message", {}).get("content"), ensure_ascii=False))
                    if ultimo is None or quando > ultimo[0]:
                        ultimo = (quando, m.group(1) if m else "uso", m.group(2) if m else "")
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


def colher_handoffs():
    novas = 0
    for h in glob.glob(os.path.join(WORKTREES, "*", ".agente-local", "HANDOFF.md")):
        pasta = os.path.dirname(os.path.dirname(h))
        for n, linha in enumerate(ler(h).splitlines()):
            m = re.match(r"\s*- \[ \] \[local:(resumo|testes|revisao|patch)\]\s*(.+)", linha)
            if not m:
                continue
            ident = f"h-{os.path.basename(pasta)[-8:]}-{n}"
            if any(os.path.exists(os.path.join(d, ident + ".json")) for d in (FILA, FEITO, FALHOU)):
                continue
            tarefa = {"id": ident, "tipo": m.group(1), "pasta": pasta, "texto": m.group(2),
                      "arquivos": re.findall(r"`([\w./-]+\.\w+)`", m.group(2)), "so_no_limite": True}
            with open(os.path.join(FILA, ident + ".json"), "w", encoding="utf-8") as f:
                json.dump(tarefa, f, ensure_ascii=False, indent=1)
            novas += 1
    if novas:
        log(f"handoff: {novas} tarefa(s) nova(s) colhida(s)")


# ---------------- laço ----------------
def gravar_estado(**kw):
    with open(ESTADO, "w", encoding="utf-8") as f:
        json.dump(dict(kw, modelo_texto=MODELO_TEXTO, modelo_codigo=MODELO_CODIGO,
                       atualizado=dt.datetime.now().isoformat(timespec="seconds")), f, ensure_ascii=False, indent=1)


def executar(caminho, ativo, motivo):
    t = json.loads(ler(caminho))
    t.setdefault("id", os.path.basename(caminho)[:-5])
    gravar_estado(modo="limite" if ativo else "conjunto", motivo=motivo, tarefa=t["id"])
    log(f"tarefa {t['id']} ({t['tipo']}) começou")
    inicio = time.time()
    try:
        resultado, destino = TIPOS[t["tipo"]](t), FEITO
    except Exception as e:
        t["tentativas"] = t.get("tentativas", 0) + 1
        if t["tentativas"] < MAX_TENTATIVAS:
            with open(caminho, "w", encoding="utf-8") as f:
                json.dump(t, f, ensure_ascii=False, indent=1)
            log(f"tarefa {t['id']} falhou ({type(e).__name__}: {e}); volta para a fila")
            return
        resultado, destino = f"Erro: {type(e).__name__}: {e}", FALHOU
    segundos = time.time() - inicio
    cabecalho = (f"# {t['id']} ({t['tipo']})\n\n> {t.get('texto', t.get('ref', ''))}\n\n"
                 f"_{dt.datetime.now():%Y-%m-%d %H:%M}, {segundos:.0f} s, texto `{MODELO_TEXTO}`, código "
                 f"`{MODELO_CODIGO}`. Rascunho do plantão local: revisar antes de usar._\n\n")
    with open(os.path.join(SAIDA, t["id"] + ".md"), "w", encoding="utf-8") as f:
        f.write(cabecalho + resultado + "\n")
    shutil.move(caminho, os.path.join(destino, os.path.basename(caminho)))
    log(f"tarefa {t['id']} terminou em {segundos:.0f} s → {os.path.basename(destino)}")


def passada():
    ativo, motivo = limite_ativo()
    if ativo:
        colher_handoffs()
    for caminho in sorted(glob.glob(os.path.join(FILA, "*.json"))):
        t = json.loads(ler(caminho))
        if t.get("so_no_limite") and not ativo:
            continue
        executar(caminho, ativo, motivo)
    gravar_estado(modo="limite" if ativo else "conjunto", motivo=motivo, tarefa=None)


if __name__ == "__main__":
    log(f"plantão iniciado (repo={REPO}, texto={MODELO_TEXTO}, código={MODELO_CODIGO})")
    while True:
        try:
            passada()
        except Exception as e:
            log(f"erro na passada: {type(e).__name__}: {e}")
        if "--uma-vez" in sys.argv:
            break
        time.sleep(INTERVALO)
