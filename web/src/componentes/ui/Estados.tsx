/**
 * Estados de lista. Nenhuma lista pode falhar em silêncio nem mostrar vazio
 * sem explicação — numa plataforma de transparência, "não achei nada" e
 * "a fonte não tem isso" são mensagens diferentes que o leitor precisa
 * distinguir (ver docs/FRONTEND.md § 4).
 */

export function Carregando({ rotulo }: { rotulo: string }) {
  return (
    <div className="py-8" role="status" aria-live="polite">
      <span className="sr-only">{rotulo}</span>
      <div aria-hidden="true" className="space-y-3">
        {[0, 1, 2].map((i) => (
          <div key={i} className="space-y-2">
            <div className="h-4 w-1/3 rounded bg-fundo-sutil" />
            <div className="h-3 w-full rounded bg-fundo-sutil" />
            <div className="h-3 w-4/5 rounded bg-fundo-sutil" />
          </div>
        ))}
      </div>
    </div>
  );
}

export function Vazio({
  titulo, descricao, children,
}: {
  titulo: string;
  descricao: string;
  children?: React.ReactNode;
}) {
  return (
    <div className="rounded-padrao border border-dashed border-borda px-4 py-8 text-center">
      <p className="font-medium text-texto">{titulo}</p>
      <p className="mx-auto mt-1 max-w-prose text-sm text-texto-suave">{descricao}</p>
      {children && <div className="mt-3">{children}</div>}
    </div>
  );
}

export function Erro({ aoTentarNovamente }: { aoTentarNovamente: () => void }) {
  return (
    <div
      role="alert"
      className="rounded-padrao border border-voto-nao/30 bg-voto-nao-fundo px-4 py-5 text-center"
    >
      <p className="font-medium text-texto">Não foi possível carregar estes dados</p>
      <p className="mt-1 text-sm text-texto-suave">
        A falha é da plataforma, não da fonte oficial — o dado pode existir.
      </p>
      <button
        type="button"
        onClick={aoTentarNovamente}
        className="mt-3 rounded-padrao border border-borda-forte px-3 py-1.5 text-sm font-medium text-texto hover:bg-fundo-sutil"
      >
        Tentar novamente
      </button>
    </div>
  );
}
