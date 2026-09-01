import Link from "next/link";
import type { PoliticoPerfil } from "@/lib/api/tipos";
import { rotularCargo, rotularStatusCandidatura } from "@/lib/formato";
import { AbasDeAtuacao } from "@/componentes/dominio/AbasDeAtuacao";
import { AvisoDeCobertura } from "@/componentes/dominio/AvisoDeCobertura";
import { TrajetoriaPolitica } from "@/componentes/dominio/TrajetoriaPolitica";

/**
 * O corpo inteiro da página de perfil, sem o `<article>` de fora fazer fetch
 * — quem chama já entrega o `perfil` pronto.
 *
 * Extraído de `politicos/[id]/page.tsx` para servir a DOIS caminhos de
 * renderização com o mesmo JSX (achado B2, ver
 * `docs/PLANO_CORRECAO_STATIC_PARAMS.md`):
 *
 * 1. A página estática (`page.tsx`), pré-renderizada em build time para quem
 *    tem `possuiAtuacaoLegislativa = true` — a minoria da coorte.
 * 2. A "casca" client-side (`CascaDePerfil`), que busca o perfil no
 *    navegador para todo o resto — a maioria — quando o hospedador reescreve
 *    404 para 200 na página de "não encontrado".
 *
 * Sem este componente compartilhado, os dois caminhos duplicariam o mesmo
 * JSX até divergirem um do outro sem ninguém perceber.
 */
export function PerfilDoPolitico({ perfil }: { perfil: PoliticoPerfil }) {
  const nome = perfil.nomeUrna ?? perfil.nomeCivil;
  const atual = perfil.trajetoria[0];
  const registroIrregular = atual.status !== "DEFERIDO";

  return (
    <article className="space-y-8">
      <header>
        <p className="text-sm text-texto-suave">Candidatura 2026</p>
        <h1 className="mt-1 text-2xl font-semibold tracking-tight text-texto">{nome}</h1>
        {perfil.nomeUrna && perfil.nomeUrna !== perfil.nomeCivil && (
          <p className="text-texto-tenue">{perfil.nomeCivil}</p>
        )}
        <p className="mt-1.5 text-texto-suave">
          {rotularCargo(atual.cargo)} · {atual.uf} · {atual.partidoSigla}
        </p>

        {registroIrregular && (
          <p className="mt-3 rounded-padrao border border-aviso-borda bg-aviso-fundo px-3 py-2 text-sm text-aviso-texto">
            <strong className="font-semibold">
              {rotularStatusCandidatura(atual.status)}.
            </strong>{" "}
            O registro desta candidatura não está deferido. O status é público e
            pode mudar até a eleição; a plataforma continua exibindo o histórico.
          </p>
        )}
      </header>

      {!perfil.possuiAtuacaoLegislativa && (
        <p className="rounded-padrao border border-borda bg-fundo-sutil px-4 py-3 text-texto-suave">
          Este candidato <strong className="font-semibold text-texto">não exerceu
          mandato legislativo</strong> nas Casas cobertas pela plataforma. Isso não
          significa ausência de vida pública — significa que não há proposições
          nem votos a exibir aqui.
        </p>
      )}

      <TrajetoriaPolitica trajetoria={perfil.trajetoria} />

      <section aria-labelledby="titulo-atuacao">
        <h2 id="titulo-atuacao" className="sr-only">
          Atuação legislativa
        </h2>
        <AbasDeAtuacao
          id={perfil.id}
          cobertura={perfil.cobertura}
          trajetoria={perfil.trajetoria}
        />
      </section>

      <AvisoDeCobertura cobertura={perfil.cobertura} />

      {/* O perfil é onde a comparação injusta acontece, então é aqui que os
          dois caminhos de verificação precisam estar — e não só no rodapé.
          Quem desconfia de uma linha desta página deve conseguir sair dela
          direto para a metodologia ou para o dado bruto. */}
      <aside
        aria-label="Como verificar estes dados"
        className="rounded-padrao border border-borda p-4 text-sm text-texto-suave"
      >
        <p>
          Cada matéria e votação acima traz o link para o registro oficial.
          Para entender como interpretamos cada voto, veja{" "}
          <Link href="/sobre" className="underline underline-offset-2 hover:text-texto">
            Sobre os dados
          </Link>
          ; para refazer o cruzamento por conta própria, baixe os{" "}
          <Link
            href="/dados-abertos"
            className="underline underline-offset-2 hover:text-texto"
          >
            dados abertos
          </Link>
          .
        </p>
      </aside>
    </article>
  );
}
