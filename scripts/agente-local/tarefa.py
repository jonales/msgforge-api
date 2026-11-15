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
    tarefa.py consumo                              # estimativa do plano do Claude na janela de 5 h
    tarefa.py calibrar 70 [15:40]                  # informa a % real (e a hora de volta) que você vê
    tarefa.py fila [horas]                         # gera já a fila de pendências (padrão 10 h)
    tarefa.py ao-vivo                              # acompanha no terminal quem está pensando, ao vivo
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
    base = f"{dt.datetime.now():%m%d-%H%M%S}-{tipo}"
    ident, n = base, 2
    while any(os.path.exists(os.path.join(BASE, d, ident + ext)) for d, ext in
              (("fila", ".json"), ("feito", ".json"), ("falhou", ".json"), ("saida", ".md"))):
        ident, n = f"{base}-{n}", n + 1   # duas tarefas no mesmo segundo não se sobrescrevem
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



CORES = {"Pesquisador": "36", "Redator": "35", "Revisor": "33", "Auditor": "33", "Programador": "32",
         "Consultor": "34", "Investigador de testes": "31", "Resumidor": "34", "Relator": "35"}


def ao_vivo():
    """Segue o .agente-local/ao-vivo.log e mostra, colorido, cada papel pensando e passando a vez."""
    import sys
    import time
    caminho = os.path.join(BASE, "ao-vivo.log")
    while not os.path.exists(caminho):
        time.sleep(1)
    f = open(caminho, encoding="utf-8", errors="replace")
    f.seek(max(0, os.path.getsize(caminho) - 6000))
    buf, cor, pos = f.read(), "0", f.tell()
    i = buf.find("\x1a")  # começa numa tarefa inteira
    buf = buf[i:] if i >= 0 else ""
    out = sys.stdout

    def c(codigo, texto):
        return f"\033[{codigo}m{texto}\033[0m"
    try:
        while True:
            while buf:
                m = min((buf.find(x) for x in "\x1a\x1c\x1d\x1e" if buf.find(x) >= 0), default=-1)
                if m != 0:
                    texto = buf if m < 0 else buf[:m]
                    if texto.strip():
                        out.write(c(cor, texto.strip("\n") + ("" if m < 0 else "\n")))
                    buf = "" if m < 0 else buf[m:]
                    continue
                fim = buf.find("\n")
                if fim < 0:
                    break  # marcador ainda incompleto
                tipo, campos, buf = buf[0], buf[1:fim].split("\x1f"), buf[fim + 1:]
                if tipo == "\x1a" and campos[0] == "tarefa":
                    _, ident, kind, fila, texto = (campos + [""] * 5)[:5]
                    out.write("\n" + c("1;37", f"━━ {ident} · {kind} · {fila} na fila ━━") + "\n" + c("2", texto) + "\n")
                elif tipo == "\x1a" and campos[0] == "entregue":
                    ok = campos[3] == "feito"
                    out.write("\n" + c("1;32" if ok else "1;31", f"{'✔' if ok else '✘'} {campos[1]} entregue em {campos[2]} s → {campos[3]}") + "\n")
                elif tipo == "\x1a" and campos[0] == "ocioso":
                    out.write("\n" + c("1;34", f"fila vazia: {campos[1]} pendências geradas (~{campos[2]} h)") + "\n")
                elif tipo == "\x1e":
                    papel, modelo, tokens = (campos + ["", "", ""])[:3]
                    cor = CORES.get(papel, "37")
                    out.write("\n" + c("1;" + cor, f"▶ {papel}") + c("2", f" ({modelo}, contexto ~{tokens} tokens) pensando…") + "\n")
                elif tipo == "\x1c":
                    out.write("\n" + c("1", f"↪ {campos[0]} passa a vez para {campos[1]}") + "\n")
                elif tipo == "\x1d":
                    if campos[0] == "fim":
                        out.write("\n" + c("2", f"◀ {campos[1]} s, {campos[2]} tokens") + "\n")
                    else:
                        out.write("\n" + c("31", f"◀ erro: {campos[1]}") + "\n")
                    cor = "0"
            out.flush()
            time.sleep(0.3)
            if os.path.getsize(caminho) < pos:  # girou
                f = open(caminho, encoding="utf-8", errors="replace")
            novo = f.read()
            pos = f.tell()
            buf += novo
    except KeyboardInterrupt:
        out.write("\033[0m\n")


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
sub.add_parser("consumo")
s = sub.add_parser("calibrar")
s.add_argument("porcentagem", type=float)
s.add_argument("volta", nargs="?", help="hora em que o limite volta, ex.: 15:40")
sub.add_parser("ao-vivo")
s = sub.add_parser("fila")
s.add_argument("horas", nargs="?", type=float, default=10.0)
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
elif a.cmd == "ao-vivo":
    ao_vivo()
elif a.cmd in ("consumo", "fila", "calibrar"):
    import importlib.util
    nome = "fila_pendencias" if a.cmd == "fila" else "consumo"
    spec = importlib.util.spec_from_file_location(nome, os.path.join(os.path.dirname(os.path.abspath(__file__)), nome + ".py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    if a.cmd in ("consumo", "calibrar"):
        e = mod.calibrar(a.porcentagem, a.volta) if a.cmd == "calibrar" else mod.estimar()
        print(f"janela desde {e['inicio']:%d/%m %H:%M}: {e['usado'] / 1e6:.1f} M de ~{e['orcamento'] / 1e6:.1f} M "
              f"({e['fracao']:.0%}); a fila automática sai em 90%")
    else:
        qtd, horas, rodadas = mod.gerar(a.horas)
        print(f"{qtd} tarefas na fila, ~{horas:.1f} h")
elif a.cmd == "licoes":
    print(open(LICOES, encoding="utf-8").read() if os.path.exists(LICOES) else "nenhuma lição ainda")
