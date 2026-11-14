"""Estimativa do consumo do plano do Claude na janela de 5 horas (sem API oficial para isso).

O Claude Code não registra a porcentagem do plano. Os transcritos trazem, por requisição, os tokens
de entrada, de saída e de cache, e o erro `rate_limit` quando o limite estoura ("resets 12:10pm").
Daí:
- custo de uma requisição = entrada + 1,25 x escrita de cache + 0,1 x leitura de cache + 5 x saída
  (pesos proporcionais ao preço);
- orçamento = gasto da janela (horário de volta - 5 h) até o estouro, na mediana dos estouros vistos;
- janela atual = a partir do último horário de volta que já passou (ou das últimas 5 h).
É uma estimativa (nos estouros medidos, ~20% de variação): serve de gatilho, não de contador exato.
"""
import datetime as dt
import glob
import json
import os
import re
import statistics

PROJETOS = os.path.expanduser("~/.claude/projects")
H5 = dt.timedelta(hours=5)
VOLTA = re.compile(r"resets (\d{1,2})(?::(\d{2}))?\s*(am|pm)", re.I)


def _custo(u):
    return (u.get("input_tokens", 0) + 1.25 * u.get("cache_creation_input_tokens", 0)
            + 0.1 * u.get("cache_read_input_tokens", 0) + 5 * u.get("output_tokens", 0))


def _horario_de_volta(texto, quando):
    m = VOLTA.search(texto or "")
    if not m:
        return quando + H5
    h, mi = int(m.group(1)) % 12, int(m.group(2) or 0)
    if m.group(3).lower() == "pm":
        h += 12
    volta = quando.replace(hour=h, minute=mi, second=0, microsecond=0)
    return volta if volta > quando else volta + dt.timedelta(days=1)


def _ler(dias=3):
    """(requisições {id: (quando, custo)}, estouros [(quando, volta)]) de todos os projetos."""
    reqs, estouros = {}, []
    corte = dt.datetime.now().timestamp() - dias * 86400
    for f in glob.glob(os.path.join(PROJETOS, "*", "**", "*.jsonl"), recursive=True):
        if os.path.getmtime(f) < corte:
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
                msg = d.get("message") or {}
                if d.get("isApiErrorMessage") and d.get("error") == "rate_limit":
                    texto = json.dumps(msg.get("content"), ensure_ascii=False)
                    estouros.append((quando.replace(second=0, microsecond=0), _horario_de_volta(texto, quando)))
                elif msg.get("usage") and d.get("requestId") and msg.get("model") != "<synthetic>":
                    reqs[d["requestId"]] = (quando, _custo(msg["usage"]))
    return reqs, sorted(set(estouros))


def estimar(orcamento_padrao=15e6):
    """Dicionário com usado, orçamento, fração, início da janela e quantos estouros calibraram."""
    reqs, estouros = _ler()
    amostras = []
    for quando, volta in estouros:
        gasto = sum(c for t, c in reqs.values() if volta - H5 < t <= quando)
        if gasto > 0:
            amostras.append(gasto)
    orcamento = statistics.median(amostras) if amostras else orcamento_padrao
    agora = dt.datetime.now().astimezone()
    voltas_passadas = [v for _, v in estouros if v <= agora and agora - v < H5]
    # Liberado antes da hora (o limite estourou e a volta ainda não chegou, mas o uso seguiu):
    # conta a partir do estouro
    liberados = [q for q, v in estouros if q <= agora < v]
    if voltas_passadas or liberados:
        inicio = max(voltas_passadas + liberados)
    else:
        inicio = agora - H5
    usado = sum(c for t, c in reqs.values() if t > inicio)
    return {"usado": usado, "orcamento": orcamento, "fracao": usado / orcamento if orcamento else 0.0,
            "inicio": inicio, "amostras": len(amostras)}


if __name__ == "__main__":
    e = estimar()
    print(f"janela desde {e['inicio']:%d/%m %H:%M}: {e['usado'] / 1e6:.1f} M de ~{e['orcamento'] / 1e6:.1f} M "
          f"({e['fracao']:.0%}; orçamento calibrado por {e['amostras']} estouro(s))")
