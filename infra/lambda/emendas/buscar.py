"""
Busca as emendas de um ano na API da CGU e grava o JSON no S3.

POR QUE ESTA FUNCAO EXISTE, E POR QUE EM SAO PAULO
--------------------------------------------------
A API de Dados da CGU recusa requisicao vinda de fora do Brasil. Medido em
26/09/2026, mesma URL, mesma chave:

    maquina domestica (residencial BR)   200
    ECS em us-east-1                     504  (duas execucoes, IPs distintos)
    runner do GitHub Actions (EUA)       504
    CloudShell em sa-east-1 (BR)         401  <- alcanca a API

O 504 vem ANTES da autenticacao: sem chave tambem da 504 de fora, quando o
esperado seria 401. A recusa e na borda, e nao ha cabecalho que contorne --
User-Agent de curl, de Java e ausente foram todos testados e passam de fora
do Brasil apenas pela maquina domestica.

Por isso a busca mora aqui, numa regiao brasileira, e nao na task de
ingestao: o RDS e a VPC estao em us-east-1, e mover tudo para ca por causa
da borda da CGU seria caro. Esta funcao so precisa de saida para a internet
e de escrita no S3 -- sem VPC, sem banco.

O JSON de um ano tem ~3 MB (6.311 emendas em 2025).
"""

import json
import os
import time
import urllib.error
import urllib.request

import boto3

BASE = "https://api.portaldatransparencia.gov.br/api-de-dados/emendas"

# Teto de 400 req/min no horario normal (700 entre 00:00 e 06:00), e uso
# acima SUSPENDE o token. A pausa e ADAPTATIVA: dorme so o que falta para
# fechar 250ms desde o inicio da requisicao, ~240/min no pior caso.
#
# Pausa fixa era desperdicio caro aqui. O spike mediu 1,24s por pagina; com
# 0,25s fixos em cima, 421 paginas dao 627s -- acima do timeout de 600s que
# eu havia escrito de cabeca como se fosse folga. Quando a requisicao ja
# demora mais que o intervalo, nao ha nada a esperar: a taxa ja esta baixa.
INTERVALO = 0.25
TENTATIVAS = 4
# 2025 teve 421 paginas de 15. O teto e folga com margem, e existe para a
# funcao nao girar ate o timeout se a API passar a devolver pagina sempre.
MAX_PAGINAS = 2000

BALDE = os.environ["BALDE_INGESTAO"]
SEGREDO_ARN = os.environ["SEGREDO_CHAVE_ARN"]
# O segredo vive em us-east-1 junto do resto; ARN e qualificado por regiao,
# entao a chamada atravessa sem replicar nada.
REGIAO_SEGREDO = SEGREDO_ARN.split(":")[3]


def _chave():
    sm = boto3.client("secretsmanager", region_name=REGIAO_SEGREDO)
    return sm.get_secret_value(SecretId=SEGREDO_ARN)["SecretString"]


def _pagina(chave, ano, pagina):
    """Uma pagina, com recuo. Devolve tambem quanto demorou, para a pausa."""
    url = f"{BASE}?ano={ano}&pagina={pagina}"
    pedido = urllib.request.Request(
        url, headers={"chave-api-dados": chave, "Accept": "application/json"})

    ultima = None
    for tentativa in range(1, TENTATIVAS + 1):
        try:
            with urllib.request.urlopen(pedido, timeout=90) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code in (401, 403):
                # Nao adianta insistir: chave errada ou suspensa por excesso.
                raise RuntimeError(
                    f"CGU recusou a chave (HTTP {e.code})") from None
            ultima = RuntimeError(f"CGU respondeu {e.code} na pagina {pagina}")
        except Exception as e:                      # noqa: BLE001
            ultima = RuntimeError(
                f"falha de rede na pagina {pagina}: {type(e).__name__}")
        if tentativa < TENTATIVAS:
            time.sleep(3 * tentativa)
    raise ultima


def handler(evento, _contexto):
    inicio = time.monotonic()
    ano = int(evento.get("ano") or time.gmtime().tm_year)
    chave = _chave()

    linhas = []
    pagina = 1
    while pagina <= MAX_PAGINAS:
        comeco = time.monotonic()
        lote = _pagina(chave, ano, pagina)
        if not lote:
            break
        linhas.extend(lote)
        pagina += 1
        time.sleep(max(0.0, INTERVALO - (time.monotonic() - comeco)))

    if not linhas:
        # Ano sem nenhuma linha e quase certamente defeito, nao realidade.
        # Gravar um arquivo vazio faria a ingestao apagar o que ja existe --
        # falha silenciosa, que e o que este projeto mais combate.
        raise RuntimeError(f"nenhuma emenda retornada para {ano}; nada gravado")

    duracao = time.monotonic() - inicio
    corpo = json.dumps(linhas, ensure_ascii=False).encode("utf-8")
    destino = f"entrada/emendas-{ano}.json"
    boto3.client("s3").put_object(
        Bucket=BALDE, Key=destino, Body=corpo,
        ContentType="application/json; charset=utf-8")

    resumo = {
        "ano": ano,
        "emendas": len(linhas),
        "paginas": pagina - 1,
        "bytes": len(corpo),
        # Vai no resumo de proposito: e o numero que diz se o timeout ainda
        # tem folga, sem precisar cavar o log depois.
        "segundos": round(duracao, 1),
        "destino": f"s3://{BALDE}/{destino}",
    }
    print(json.dumps(resumo, ensure_ascii=False))
    return resumo
