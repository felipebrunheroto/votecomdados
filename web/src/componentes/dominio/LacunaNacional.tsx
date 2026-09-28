import type { ResumoDeEmendas } from "@/lib/api/tipos";

/**
 * Quanto do dinheiro, no acervo inteiro, é registrado sem dizer o município.
 *
 * <h2>Por que o número é calculado e não escrito</h2>
 *
 * Este texto nasceu com "88,5%" fixo, medido sobre 2025 — o único ano
 * carregado na época. Quando 2023, 2024 e 2026 entraram, a série mostrou que
 * a proporção varia muito: emendas com município são 23,3% das linhas em 2023
 * e 12,0% em 2025.
 *
 * O número fixo passou a afirmar ao leitor algo que não valia para o acervo
 * que ele estava vendo, e ninguém teria percebido — foi preciso carregar mais
 * anos para o erro aparecer. Calculado, o texto fica certo sozinho a cada ano
 * que entra.
 */
export function LacunaNacional({ nacional }: { nacional: ResumoDeEmendas }) {
  const comCidade =
    nacional.porLocalidade.find((f) => f.localidade === "MUNICIPIO")?.desembolso ?? 0;
  const semCidade = nacional.desembolso - comCidade;

  if (nacional.desembolso <= 0 || semCidade <= 0) return null;

  const percentual = Math.round((semCidade / nacional.desembolso) * 100);

  return (
    <p className="mt-4 rounded border border-aviso-borda bg-aviso-fundo p-3 text-sm text-aviso-texto">
      <strong>Esta lista é incompleta, e não há como completá-la.</strong>{" "}
      <strong>{percentual}%</strong> do dinheiro de emendas é registrado sem
      discriminar município — parte dele pode ter vindo para cá sem aparecer
      aqui.{" "}
      <strong>
        Um valor baixo nesta página não significa que a cidade recebeu pouco.
      </strong>
    </p>
  );
}

/** A mesma medida, para a tela de cidade sem registro. */
export function ExplicacaoDaAusencia({ nacional }: { nacional: ResumoDeEmendas }) {
  const comCidade =
    nacional.porLocalidade.find((f) => f.localidade === "MUNICIPIO")?.desembolso ?? 0;
  const semCidade = nacional.desembolso - comCidade;
  const percentual = nacional.desembolso > 0
    ? Math.round((semCidade / nacional.desembolso) * 100)
    : null;

  return (
    <p className="mx-auto mt-2 max-w-prose text-sm text-texto-suave">
      Isso <strong>não</strong> significa que a cidade não recebeu emendas.
      Significa que nenhuma emenda federal registrou este município como
      destino
      {percentual !== null && (
        <> — e <strong>{percentual}% do dinheiro é registrado sem dizer a cidade</strong></>
      )}
      .
    </p>
  );
}
