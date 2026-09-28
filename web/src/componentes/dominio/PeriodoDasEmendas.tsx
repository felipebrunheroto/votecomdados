import type { PeriodoCoberto } from "@/lib/api/tipos";

/**
 * Diz de quando são os números.
 *
 * <h2>Por que isto não é detalhe</h2>
 *
 * Sem ele a tela dizia "R$ 68.149.687 desembolsados no total", e "no total"
 * é lido como "em geral" — quando significava "em 2025", o único ano
 * carregado. Um leitor concluiria que aquilo é a atuação inteira da pessoa.
 *
 * O período é da nossa COBERTURA, não do recorte exibido: um parlamentar com
 * emenda só em 2025 continua mostrando o intervalo completo, porque a
 * ausência nos outros anos é informação dele, não limite nosso.
 */
export function PeriodoDasEmendas({ periodo }: { periodo: PeriodoCoberto | null }) {
  if (!periodo) return null;

  const intervalo = periodo.anoInicio === periodo.anoFim
    ? `${periodo.anoInicio}`
    : `${periodo.anoInicio} a ${periodo.anoFim}`;

  return (
    <p className="text-xs uppercase tracking-wide text-texto-tenue">
      Emendas de {intervalo}
    </p>
  );
}
