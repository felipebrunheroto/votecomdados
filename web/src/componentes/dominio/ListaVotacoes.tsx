import Link from "next/link";
import type { VotacaoDoPolitico } from "@/lib/api/tipos";
import { formatarDataHora, rotularCasa } from "@/lib/formato";
import { EtiquetaVoto } from "./EtiquetaVoto";
import { LinkFonteOficial } from "./LinkFonteOficial";

export function ListaVotacoes({ itens }: { itens: VotacaoDoPolitico[] }) {
  if (itens.length === 0) return null;

  return (
    <ul className="divide-y divide-borda">
      {itens.map((v) => (
        <li key={v.votacaoId} className="py-4">
          <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
            <span className="text-sm text-texto-suave">
              {formatarDataHora(v.dataVotacao)} · {rotularCasa(v.casa)}
            </span>
          </div>

          <h3 className="mt-1 font-medium">
            <Link
              href={`/votacoes/${v.votacaoId}`}
              className="text-texto underline decoration-borda-forte underline-offset-2 hover:decoration-acento"
            >
              {v.descricao}
            </Link>
          </h3>

          <div className="mt-2.5">
            <EtiquetaVoto voto={v.voto} votoOrigem={v.votoOrigem}
                          origemRegistro={v.origemRegistro} ambito={v.ambito} />
          </div>

          {/* Nota de metodologia e observação nunca são escondidas atrás de
              interação: o leitor precisa vê-las junto do voto, ou o dado é
              apresentado sem o contexto que impede a leitura errada. */}
          {v.notaMetodologica && (
            <p className="mt-2 border-l-2 border-borda pl-3 text-sm text-texto-suave">
              {v.notaMetodologica}
            </p>
          )}
          {v.observacao && (
            <p className="mt-2 border-l-2 border-borda pl-3 text-sm text-texto-suave">
              {v.observacao}
            </p>
          )}

          <div className="mt-2.5 flex flex-wrap items-center gap-x-4 gap-y-1">
            {v.aprovada !== null && (
              <span className="text-sm text-texto-tenue">
                Matéria {v.aprovada ? "aprovada" : "rejeitada"}
              </span>
            )}
            <LinkFonteOficial
              href={v.urlFonte}
              descricaoAcessivel={`votação de ${formatarDataHora(v.dataVotacao)}`}
            >
              Votação oficial
            </LinkFonteOficial>
          </div>
        </li>
      ))}
    </ul>
  );
}
