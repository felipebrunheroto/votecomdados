import type { AmbitoVotacao, OrigemRegistro, TipoVoto } from "@/lib/api/tipos";

/**
 * Exibe o voto de um parlamentar.
 *
 * Seis regras que não são estéticas, são de neutralidade:
 *
 * 1. O rótulo ORIGINAL da fonte aparece sempre. O enum é interpretação nossa;
 *    "Favorável ao parecer" e "Sim" não são a mesma coisa, e a Alesp emite 477
 *    descrições em texto livre para os 8 códigos de voto dela.
 * 2. Cor nunca é o único sinal — sempre acompanha ícone e texto. Exigência de
 *    WCAG e proteção contra leitura partidária de verde/vermelho.
 * 3. Voto de COMISSÃO é marcado como tal, porque não tem o mesmo peso de uma
 *    deliberação de plenário.
 * 4. Voto DERIVADO é marcado como cálculo nosso. **Na Câmara** nenhuma fonte
 *    publica "faltou": ausência e licença saem do cruzamento com quem estava em
 *    exercício na data. Exibi-las como registro da Casa seria atribuir à
 *    origem uma afirmação que ela não fez. No Senado é o contrário — a fonte
 *    declara tudo, e nada ali é derivado.
 * 5. `SECRETO` NÃO é recusa a votar. Em 53% das votações de plenário do Senado
 *    a deliberação é secreta: a Casa registra que o parlamentar participou, e
 *    não como votou. Ler isso como omissão inverteria o sentido do fato.
 * 6. `VOTO_EM_SEPARADO` também NÃO é recusa a votar — é o contrário. O
 *    parlamentar votou apresentando parecer escrito divergente do relator. A
 *    Alesp não publica se o divergente era favorável ou contrário ao projeto,
 *    e o rótulo original ao lado é o que dá a pista quando existe.
 */

const APARENCIA: Record<TipoVoto, { rotulo: string; icone: string; classe: string }> = {
  SIM: { rotulo: "Sim", icone: "✓", classe: "bg-voto-sim-fundo text-voto-sim" },
  NAO: { rotulo: "Não", icone: "✕", classe: "bg-voto-nao-fundo text-voto-nao" },
  ABSTENCAO: { rotulo: "Abstenção", icone: "○", classe: "bg-voto-neutro-fundo text-voto-neutro" },
  BRANCO: { rotulo: "Em branco", icone: "▢", classe: "bg-voto-neutro-fundo text-voto-neutro" },
  AUSENTE: { rotulo: "Ausente", icone: "–", classe: "bg-voto-neutro-fundo text-voto-neutro" },
  LICENCIADO: { rotulo: "Licenciado", icone: "◐", classe: "bg-voto-neutro-fundo text-voto-neutro" },
  AUSENCIA_JUSTIFICADA: { rotulo: "Ausência justificada", icone: "◑", classe: "bg-voto-neutro-fundo text-voto-neutro" },
  PRESENTE_NAO_VOTOU: { rotulo: "Presente, não votou", icone: "◌", classe: "bg-voto-neutro-fundo text-voto-neutro" },
  SECRETO: { rotulo: "Votação secreta", icone: "🔒", classe: "bg-voto-neutro-fundo text-voto-neutro" },
  OBSTRUCAO: { rotulo: "Obstrução", icone: "⊘", classe: "bg-voto-neutro-fundo text-voto-neutro" },
  VOTO_EM_SEPARADO: { rotulo: "Voto em separado", icone: "⇄", classe: "bg-voto-neutro-fundo text-voto-neutro" },
  ART_17: { rotulo: "Art. 17", icone: "§", classe: "bg-voto-neutro-fundo text-voto-neutro" },
};

export function EtiquetaVoto({
  voto, votoOrigem, origemRegistro, ambito,
}: {
  voto: TipoVoto | null;
  votoOrigem: string | null;
  /** `null` em votação simbólica, que sai pelo caminho de cima. */
  origemRegistro: OrigemRegistro | null;
  ambito: AmbitoVotacao;
}) {
  if (voto === null) {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-padrao bg-voto-neutro-fundo px-2 py-1 text-sm text-voto-neutro">
        <span aria-hidden="true">—</span>
        <span>Sem voto individual</span>
      </span>
    );
  }

  const { rotulo, icone, classe } = APARENCIA[voto];

  return (
    <span className="inline-flex flex-wrap items-center gap-x-2 gap-y-1">
      <span
        className={`inline-flex items-center gap-1.5 rounded-padrao px-2 py-1 text-sm font-medium ${classe}`}
      >
        <span aria-hidden="true">{icone}</span>
        <span>{rotulo}</span>
      </span>

      {/* O rótulo da fonte só é redundante quando é idêntico ao normalizado. */}
      {votoOrigem && votoOrigem.toLowerCase() !== rotulo.toLowerCase() && (
        <span className="text-sm text-texto-suave">
          registrado como{" "}
          <span className="font-medium text-texto">&ldquo;{votoOrigem}&rdquo;</span>
        </span>
      )}

      {origemRegistro === "DERIVADO" && (
        <span
          className="rounded-padrao border border-borda px-1.5 py-0.5 text-xs text-texto-suave"
          title="A Casa publica apenas quem registrou voto. Esta linha vem do cruzamento com a lista de quem estava em exercício na data."
        >
          apurado pela plataforma
        </span>
      )}

      {ambito === "COMISSAO" && (
        <span className="rounded-padrao border border-borda px-1.5 py-0.5 text-xs text-texto-suave">
          em comissão
        </span>
      )}
    </span>
  );
}
