import type { ResumoDeEmendas } from "@/lib/api/tipos";
import { formatarReais } from "@/lib/formato";

/**
 * Declara quanto do dinheiro NÃO tem município identificado.
 *
 * <h2>Por que isso é obrigatório, e não um rodapé</h2>
 *
 * A maior parte do dinheiro é registrada sem município. A proporção exata
 * VARIA por ano — medida em 28/09/2026 sobre 2023–2026, emendas com município
 * são 23,3% das linhas em 2023 e 12,0% em 2025 —, por isso este componente
 * calcula do resumo recebido em vez de citar um número escrito.
 *
 * Uma emenda "Múltiplo" cobre várias localidades numa linha só, **sem
 * discriminar quanto coube a cada uma**, e não é decomponível: o endpoint de
 * documentos da CGU traz só número de empenho.
 *
 * Sem este bloco, a página mostraria as emendas com cidade e calaria sobre o
 * resto — parecendo completa. O leitor concluiria que aquele é o total.
 */
export function LacunaDeEmendas({ resumo }: { resumo: ResumoDeEmendas }) {
  const comCidade =
    resumo.porLocalidade.find((f) => f.localidade === "MUNICIPIO")?.desembolso ?? 0;
  const semCidade = resumo.desembolso - comCidade;

  if (semCidade <= 0) return null;

  const percentual = resumo.desembolso > 0
    ? Math.round((semCidade / resumo.desembolso) * 100)
    : 0;

  return (
    <p className="mt-4 rounded border border-aviso-borda bg-aviso-fundo p-3 text-sm text-aviso-texto">
      <strong>{formatarReais(semCidade)}</strong> não aparecem nesta lista
      {percentual > 0 && <> — <strong>{percentual}%</strong> do total</>}. A fonte
      registra essas emendas sem dizer o município: são &ldquo;Múltiplo&rdquo;,
      o estado inteiro, ou de alcance nacional.{" "}
      <strong>Não significa que o dinheiro não chegou a cidade nenhuma</strong>;
      significa que a fonte não diz a quais.
    </p>
  );
}

/**
 * Explica a diferença entre empenhado, pago e restos a pagar.
 *
 * Aparece só quando há restos pagos, porque é aí que a distinção deixa de ser
 * acadêmica: Santos, em 2025, tem `pago = R$ 0,00` e recebeu R$ 600 mil por
 * restos. Sem esta nota, a pessoa lê a coluna "pago" e conclui o oposto.
 */
export function NotaDeExecucao({ resumo }: { resumo: ResumoDeEmendas }) {
  const temRestos = resumo.restoPago > 0;

  return (
    <p className="mt-3 text-xs text-texto-tenue">
      <strong>Empenhado</strong> é valor reservado; <strong>pago</strong> é
      dinheiro que saiu no orçamento do ano.
      {temRestos && (
        <>
          {" "}
          <strong>Restos a pagar</strong> são de orçamentos anteriores,
          executados agora — por isso o total desembolsado pode ser maior que a
          coluna &ldquo;pago&rdquo;.
        </>
      )}{" "}
      O parlamentar indica a emenda; quem executa é o Poder Executivo.
    </p>
  );
}
