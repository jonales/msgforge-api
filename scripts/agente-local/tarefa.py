"""Passa tarefas para o plantão local e consulta o resultado.

    tarefa.py refinar "pedido cru"                 # vira especificação pronta para o Claude
    tarefa.py pergunta "como funciona X?" [-a arquivo ...]
    tarefa.py resumo "o que resumir" -a arquivo [arquivo ...]
    tarefa.py revisao --ref minha-branch [--base origin/main]
    tarefa.py testes [--ref main] [--classe XTest | --it XIT]
    tarefa.py patch "alteração pequena" -a arquivo [...] [--ref main] [--verificacao mvn-compile]
    tarefa.py status                               # modo, fila e últimas saídas
    tarefa.py ver [id]                             # mostra a saída (sem id: a mais recente)
    tarefa.py nota <id> "lição"                    # ensina o plantão (vai para licoes.md)
    tarefa.py licoes                               # lista o que ele já aprendeu
"""
import argparse
import datetime as dt
import glob
import json
import os
import subprocess
import sys

comum = subprocess.run(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
                       cwd=os.path.dirname(os.path.abspath(__file__)), capture_output=True, text=True).stdout.strip()
REPO = os.environ.get("PLANTAO_REPO") or os.path.dirname(comum)
BASE = os.path.join(REPO, ".agente-local")
FILA, SAIDA, LICOES = (os.path.join(BASE, p) for p in ("fila", "saida", "licoes.md"))
for p in (FILA, SAIDA):
    os.makedirs(p, exist_ok=True)


def enfileirar(tipo, **campos):
    ident = f"{dt.datetime.now():%m%d-%H%M%S}-{tipo}"
    tarefa = {"id": ident, "tipo": tipo, **{k: v for k, v in campos.items() if v not in (None, [], "")}}
    with open(os.path.join(FILA, ident + ".json"), "w", encoding="utf-8") as f:
        json.dump(tarefa, f, ensure_ascii=False, indent=1)
    vivo = subprocess.run(["pgrep", "-f", "agente-local/plantao.py"], capture_output=True).returncode == 0
    print(f"na fila: {ident}" + ("" if vivo else "  (atenção: o plantão não está rodando)"))


def status():
    try:
        print(json.dumps(json.load(open(os.path.join(BASE, "estado.json"))), ensure_ascii=False, indent=1))
    except FileNotFoundError:
        print("sem estado ainda")
    fila = sorted(os.path.basename(f)[:-5] for f in glob.glob(os.path.join(FILA, "*.json")))
    print("fila:", ", ".join(fila) or "vazia")
    for f in sorted(glob.glob(os.path.join(SAIDA, "*.md")), key=os.path.getmtime)[-5:]:
        print(f"  saída: {os.path.basename(f)[:-3]}")


def ver(ident):
    saidas = sorted(glob.glob(os.path.join(SAIDA, "*.md")), key=os.path.getmtime)
    alvo = os.path.join(SAIDA, ident + ".md") if ident else (saidas[-1] if saidas else None)
    if not alvo or not os.path.exists(alvo):
        sys.exit("saída não encontrada (ainda na fila? veja: tarefa.py status)")
    print(open(alvo, encoding="utf-8").read())


def nota(ident, texto):
    with open(LICOES, "a", encoding="utf-8") as f:
        f.write(f"- {texto.strip()} (de {ident}, {dt.date.today()})\n")
    print("lição registrada; vale a partir da próxima tarefa")


p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
sub = p.add_subparsers(dest="cmd", required=True)
for nome in ("refinar", "pergunta", "resumo", "patch"):
    s = sub.add_parser(nome)
    s.add_argument("texto")
    s.add_argument("-a", "--arquivos", nargs="+", default=[])
    if nome == "patch":
        s.add_argument("--ref", default="main")
        s.add_argument("--verificacao", default="mvn-compile",
                       choices=["mvn-compile", "mvn-test", "mvn-test-classe", "mvn-it-classe"])
        s.add_argument("--classe")
s = sub.add_parser("revisao")
s.add_argument("--ref", required=True)
s.add_argument("--base", default="origin/main")
s = sub.add_parser("testes")
s.add_argument("--ref", default="main")
g = s.add_mutually_exclusive_group()
g.add_argument("--classe", help="só esta classe de teste unitário")
g.add_argument("--it", help="só esta classe de teste de integração")
sub.add_parser("status")
s = sub.add_parser("ver")
s.add_argument("id", nargs="?")
s = sub.add_parser("nota")
s.add_argument("id")
s.add_argument("texto")
sub.add_parser("licoes")
a = p.parse_args()

if a.cmd in ("refinar", "pergunta", "resumo"):
    enfileirar(a.cmd, texto=a.texto, arquivos=a.arquivos)
elif a.cmd == "patch":
    enfileirar("patch", texto=a.texto, arquivos=a.arquivos, ref=a.ref, verificacao=a.verificacao,
               params={"classe": a.classe} if a.classe else None)
elif a.cmd == "revisao":
    enfileirar("revisao", ref=a.ref, base=a.base)
elif a.cmd == "testes":
    if a.classe:
        enfileirar("testes", ref=a.ref, comando="mvn-test-classe", params={"classe": a.classe})
    elif a.it:
        enfileirar("testes", ref=a.ref, comando="mvn-it-classe", params={"classe": a.it})
    else:
        enfileirar("testes", ref=a.ref)
elif a.cmd == "status":
    status()
elif a.cmd == "ver":
    ver(a.id)
elif a.cmd == "nota":
    nota(a.id, a.texto)
elif a.cmd == "licoes":
    print(open(LICOES, encoding="utf-8").read() if os.path.exists(LICOES) else "nenhuma lição ainda")
