#!/usr/bin/env python3
"""
Spike de medição da API de emendas do Portal da Transparência.

Responde, com dado e não com estimativa, as incógnitas do discovery
(docs/DISCOVERY_EMENDAS.md):

  1. Volume e tamanho de página de um ano.
  2. O que é o campo `autor` — e se dá para ligá-lo à nossa base.
  3. Em que formato vem `localidadeDoGasto` — e, principalmente, QUE FRAÇÃO
     das emendas é atribuível a um município.

A terceira é a que decide a funcionalidade. Se metade do dinheiro vier como
"Múltiplo" ou "Nacional", "quanto foi para a sua cidade" não é uma pergunta
que esta fonte responde por inteiro, e a página precisa declarar a lacuna.

USO:
    export PORTAL_TRANSPARENCIA_TOKEN='...'      # nao fica no historico se
                                                 # a linha comecar com espaco
    python3 tools/spike-emendas.py --ano 2025

O token e lido do ambiente e NUNCA e impresso, nem em erro. Ele nao entra
no repositorio: se o spike virar ingestao, o lugar dele e o Secrets Manager,
como o pepper do CPF.
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from collections import Counter

BASE = "https://api.portaldatransparencia.gov.br/api-de-dados/emendas"

# 400 req/min no horario normal, 700 entre 00h e 06h. Ficamos MUITO abaixo:
# um spike que derruba o limite do token queima o acesso de todo mundo que
# usa a mesma chave, e o Portal suspende sem aviso.
PAUSA_ENTRE_PAGINAS = 0.25


def buscar(token, ano, pagina):
    url = f"{BASE}?ano={ano}&pagina={pagina}"
    req = urllib.request.Request(url, headers={
        "chave-api-dados": token,
        "Accept": "application/json",
    })
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def valor(bruto):
    """
    "2.359.960,00" -> 2359960.0

    Armadilha real: parsing ingenuo com float() devolve 0 silenciosamente, e
    zero indistinguivel de "nao ha dado" e exatamente o tipo de numero errado
    que esta plataforma nao pode publicar.
    """
    if bruto is None:
        return None
    t = str(bruto).strip()
    if not t:
        return None
    t = t.replace(".", "").replace(",", ".")
    try:
        return float(t)
    except ValueError:
        return None  # devolve ausencia, nunca zero


def formato_da_localidade(s):
    """Classifica `localidadeDoGasto` nas formas observadas."""
    if s is None or not str(s).strip():
        return "VAZIO"
    t = str(s).strip()
    if re.fullmatch(r".+ - [A-Z]{2}", t):
        return "MUNICIPIO"           # "ITAMARAJU - BA"
    if re.search(r"\(UF\)$", t, re.I):
        return "ESTADO"              # "BAHIA (UF)"
    if t.lower() == "nacional":
        return "NACIONAL"
    if t.lower().startswith("m") and "ltiplo" in t.lower():
        return "MULTIPLO"            # "Múltiplo"
    return "OUTRO"


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--ano", type=int, required=True)
    p.add_argument("--max-paginas", type=int, default=200,
                   help="teto de seguranca; 0 = sem teto")
    args = p.parse_args()

    token = os.environ.get("PORTAL_TRANSPARENCIA_TOKEN")
    if not token:
        print("PORTAL_TRANSPARENCIA_TOKEN nao definido no ambiente.",
              file=sys.stderr)
        print("Cadastre em portaldatransparencia.gov.br/api-de-dados/"
              "cadastrar-email", file=sys.stderr)
        return 2

    linhas = []
    pagina = 1
    tamanho_pagina = None
    inicio = time.time()

    while True:
        if args.max_paginas and pagina > args.max_paginas:
            print(f"  (teto de {args.max_paginas} paginas atingido)")
            break
        try:
            lote = buscar(token, args.ano, pagina)
        except urllib.error.HTTPError as e:
            # Nunca ecoar o token: a mensagem de erro do Portal nao o contem,
            # mas a URL sim em algumas libs. Aqui so o codigo importa.
            print(f"  HTTP {e.code} na pagina {pagina}", file=sys.stderr)
            break
        if not lote:
            break
        if tamanho_pagina is None:
            tamanho_pagina = len(lote)
        linhas.extend(lote)
        pagina += 1
        time.sleep(PAUSA_ENTRE_PAGINAS)

    seg = time.time() - inicio
    if not linhas:
        print("nenhuma linha retornada — verifique o ano e o token")
        return 1

    print(f"\n=== 1. VOLUME ({args.ano}) ===")
    print(f"linhas: {len(linhas)}  |  paginas: {pagina - 1}  "
          f"|  por pagina: {tamanho_pagina}  |  {seg:.1f}s")

    print(f"\n=== 2. AUTOR ===")
    exemplo = linhas[0]
    print(f"campos presentes: {sorted(exemplo.keys())}")
    print(f"autor     = {exemplo.get('autor')!r}")
    print(f"nomeAutor = {exemplo.get('nomeAutor')!r}")
    autores = {(l.get("autor"), l.get("nomeAutor")) for l in linhas}
    print(f"autores distintos: {len(autores)}")
    # "4290 - ABILIO BRUNINI": o codigo vem embutido no nome?
    com_codigo = sum(1 for _, n in autores
                     if n and re.match(r"^\d+\s*-\s*", str(n)))
    print(f"nomeAutor no formato '<codigo> - <NOME>': {com_codigo}/{len(autores)}")
    print("amostra de autores:")
    for a, n in list(sorted(autores, key=lambda x: str(x[0])))[:5]:
        print(f"   autor={a!r}  nomeAutor={n!r}")

    print(f"\n=== 3. LOCALIDADE — a pergunta que decide a funcionalidade ===")
    formas = Counter(formato_da_localidade(l.get("localidadeDoGasto"))
                     for l in linhas)
    total = sum(formas.values())
    for forma, n in formas.most_common():
        print(f"   {forma:10} {n:7}  {100 * n / total:5.1f}%")

    # A fracao que importa nao e de LINHAS, e de DINHEIRO.
    print("\n   por valor pago:")
    dinheiro = Counter()
    sem_valor = 0
    for l in linhas:
        v = valor(l.get("valorPago"))
        if v is None:
            sem_valor += 1
            continue
        dinheiro[formato_da_localidade(l.get("localidadeDoGasto"))] += v
    tot = sum(dinheiro.values()) or 1
    for forma, v in dinheiro.most_common():
        print(f"   {forma:10} R$ {v:16,.2f}  {100 * v / tot:5.1f}%")
    if sem_valor:
        print(f"   ({sem_valor} linha(s) sem valorPago legivel)")

    print(f"\n=== 4. FORMATO DOS VALORES ===")
    brutos = [l.get("valorPago") for l in linhas[:5]]
    print(f"amostra crua : {brutos}")
    print(f"convertidos  : {[valor(b) for b in brutos]}")

    print(f"\n=== 5. AMOSTRA DE LOCALIDADES ===")
    for s, n in Counter(str(l.get("localidadeDoGasto"))
                        for l in linhas).most_common(8):
        print(f"   {n:6}x  {s!r}")

    saida = f"emendas-{args.ano}.json"
    with open(saida, "w", encoding="utf-8") as f:
        json.dump(linhas, f, ensure_ascii=False)
    print(f"\nlinhas cruas salvas em {saida} "
          f"(nao commitar — dado bruto de terceiro)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
