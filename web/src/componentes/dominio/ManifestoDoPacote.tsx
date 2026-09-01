"use client";

import { useEffect, useState } from "react";
import { formatarDataDoDia } from "@/lib/formato";

/**
 * O que `manifesto.json` do pacote publicado declara.
 *
 * Campos em snake_case porque o arquivo é gerado direto da view
 * `dados_abertos.manifesto` pelo Postgres — o pacote é dado aberto, e renomear
 * chaves aqui criaria uma divergência entre o que a página mostra e o que quem
 * baixa o arquivo encontra.
 */
type Manifesto = {
  gerado_em: string;
  linhas_por_tabela: Record<string, number>;
  votos_derivados_por_nos: number;
  vinculos_por_similaridade: number;
  vinculos_fuzzy_sem_revisao_humana: number;
};

const NOME_TABELA: Record<string, string> = {
  politico: "pessoas",
  candidatura: "candidaturas",
  identificador_externo: "vínculos com as Casas",
  proposicao: "matérias",
  votacao: "votações",
  voto_nominal: "votos individuais",
  mandato_exercicio: "períodos de mandato",
};

/**
 * Lê o manifesto do pacote publicado, em TEMPO DE EXECUÇÃO.
 *
 * <h2>Por que não é conteúdo estático da página</h2>
 *
 * O HTML é gerado no build e o pacote é regerado pela ingestão, diariamente.
 * Números escritos à mão aqui envelheceriam em silêncio — e numa página cujo
 * assunto é "confira o nosso trabalho", número desatualizado é pior que número
 * ausente. Mesmo raciocínio do `FrescorDosDados`.
 *
 * <h2>O endereço de citação sai daqui</h2>
 *
 * O build não sabe qual é o instantâneo mais recente: quem sabe é o próprio
 * pacote. Ler `gerado_em` é o que permite oferecer o diretório **datado** —
 * o endereço que serve de citação — em vez de mandar o leitor citar `latest`,
 * que muda embaixo de quem citou.
 */
export function ManifestoDoPacote() {
  const [manifesto, setManifesto] = useState<Manifesto | null>(null);
  const [erro, setErro] = useState(false);

  useEffect(() => {
    let cancelado = false;
    fetch("/dados-abertos/latest/manifesto.json", { headers: { Accept: "application/json" } })
      .then((r) => {
        if (!r.ok) throw new Error(String(r.status));
        return r.json() as Promise<Manifesto>;
      })
      .then((m) => { if (!cancelado) setManifesto(m); })
      .catch(() => { if (!cancelado) setErro(true); });
    return () => { cancelado = true; };
  }, []);

  if (erro) {
    return (
      <p className="text-sm text-texto-suave">
        Não foi possível ler o manifesto do pacote agora. O download continua
        disponível em{" "}
        <a href="/dados-abertos/latest/" className="underline underline-offset-2">
          /dados-abertos/latest/
        </a>
        .
      </p>
    );
  }

  if (!manifesto) {
    return <p className="text-sm text-texto-tenue">Consultando o pacote publicado…</p>;
  }

  const linhas = Object.entries(manifesto.linhas_por_tabela ?? {})
    .filter(([tabela]) => tabela in NOME_TABELA);

  return (
    <div className="space-y-4">
      <p className="text-texto-suave">
        O instantâneo mais recente é de{" "}
        <strong className="font-semibold text-texto">
          {formatarDataDoDia(manifesto.gerado_em)}
        </strong>
        . Ele contém:
      </p>

      <ul className="grid gap-x-6 gap-y-1.5 sm:grid-cols-2">
        {linhas.map(([tabela, total]) => (
          <li key={tabela} className="flex items-baseline justify-between gap-3 border-b border-borda py-1.5">
            <span className="text-texto-suave">{NOME_TABELA[tabela]}</span>
            <span className="font-medium tabular-nums text-texto">
              {total.toLocaleString("pt-BR")}
            </span>
          </li>
        ))}
      </ul>

      {/* Os dois números desconfortáveis. Publicá-los é o que separa dados
          abertos de peça de marketing — e são exatamente por onde alguém
          deveria começar a conferir o nosso trabalho. */}
      <dl className="space-y-3 rounded-padrao border border-borda p-4">
        <div>
          <dt className="font-medium text-texto">
            {manifesto.votos_derivados_por_nos.toLocaleString("pt-BR")} votos são
            cálculo nosso, não registro da Casa
          </dt>
          <dd className="text-sm text-texto-suave">
            Ausências e licenças na Câmara, que a fonte não publica. Vêm marcados
            como <code className="text-xs">DERIVADO</code> no arquivo.
          </dd>
        </div>
        <div>
          <dt className="font-medium text-texto">
            {manifesto.vinculos_por_similaridade.toLocaleString("pt-BR")} vínculos
            foram feitos por semelhança de nome
          </dt>
          <dd className="text-sm text-texto-suave">
            Destes,{" "}
            <strong className="font-semibold text-texto">
              {manifesto.vinculos_fuzzy_sem_revisao_humana.toLocaleString("pt-BR")}
            </strong>{" "}
            ainda não passaram por revisão humana. São o ponto mais frágil da
            base, e o melhor lugar para começar a conferir.
          </dd>
        </div>
      </dl>

      <p className="text-sm text-texto-suave">
        Para citar, use o endereço datado —{" "}
        <a
          href={`/dados-abertos/${manifesto.gerado_em}/`}
          className="underline underline-offset-2 hover:text-texto"
        >
          <code className="text-xs">/dados-abertos/{manifesto.gerado_em}/</code>
        </a>{" "}
        — e não <code className="text-xs">latest</code>, que muda.
      </p>
    </div>
  );
}
