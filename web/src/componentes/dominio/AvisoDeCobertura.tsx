import type { Cobertura, StatusCobertura } from "@/lib/api/tipos";
import { rotularEsfera } from "@/lib/formato";

/**
 * O componente mais importante da plataforma para a honestidade do produto.
 *
 * Sem ele, um vereador com dez anos de mandato aparece com trajetória rica e
 * nenhuma matéria, e o leitor conclui "não fez nada" — quando a verdade é que
 * não cobrimos câmaras municipais. Comparar candidatos de níveis ou estados
 * diferentes sem este contexto é comparar o quanto cada Casa publica, não o
 * que cada pessoa fez.
 *
 * Os três status geram mensagens deliberadamente diferentes: dizer "não
 * existe" quando é "não fizemos" engana, e o inverso cria expectativa de algo
 * que a fonte nunca publicará.
 */

const TITULO: Record<StatusCobertura, string> = {
  DISPONIVEL: "Coberto",
  NAO_PUBLICADO_PELA_FONTE: "A fonte não publica",
  FORA_DO_ESCOPO_MVP: "Ainda não coberto",
};

/** Sem nomear o recurso, duas linhas da mesma esfera ficam indistinguíveis. */
const ROTULO_RECURSO: Record<string, string> = {
  proposicao: "Projetos apresentados",
  voto_nominal: "Votos em plenário",
  votacao_comissao: "Votos em comissão",
  votacao_plenario: "Votações de plenário",
  candidatura: "Trajetória eleitoral",
};

function Linha({ item }: { item: Cobertura }) {
  const lacuna = item.status !== "DISPONIVEL";
  return (
    <li className="flex gap-3 py-2">
      <span
        aria-hidden="true"
        className={`mt-1 h-2 w-2 shrink-0 rounded-full ${lacuna ? "bg-borda-forte" : "bg-acento"}`}
      />
      <div className="min-w-0">
        <p className="text-sm font-medium text-texto">
          {ROTULO_RECURSO[item.recurso] ?? item.recurso}
          <span className="font-normal text-texto-suave">
            {" · "}
            {rotularEsfera(item.esfera)}
            {item.uf ? ` · ${item.uf}` : ""}
            {" — "}
            {TITULO[item.status]}
          </span>
        </p>
        <p className="text-sm text-texto-suave">{item.observacao}</p>
      </div>
    </li>
  );
}

export function AvisoDeCobertura({ cobertura }: { cobertura: Cobertura[] }) {
  const lacunas = cobertura.filter((c) => c.status !== "DISPONIVEL");

  return (
    <section
      aria-labelledby="titulo-cobertura"
      className="rounded-padrao border border-aviso-borda bg-aviso-fundo p-4"
    >
      <h2 id="titulo-cobertura" className="text-sm font-semibold text-aviso-texto">
        O que esta página cobre — e o que não cobre
      </h2>
      <p className="mt-1 text-sm text-aviso-texto">
        {lacunas.length > 0
          ? "Há lacunas nos dados deste candidato. Elas não indicam inatividade: indicam o que cada fonte oficial publica e até onde a plataforma chega hoje."
          : "Todas as fontes previstas para este candidato estão cobertas."}
      </p>
      <ul className="mt-3 divide-y divide-aviso-borda/60">
        {cobertura.map((item) => (
          <Linha key={`${item.esfera}-${item.uf ?? "todas"}-${item.recurso}`} item={item} />
        ))}
      </ul>
    </section>
  );
}
