"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";

/** `[nome, uf, codigoIbge]` — forma compacta, ver public/municipios.json. */
type Municipio = [string, string, number];

/**
 * Sem acento e em caixa alta, para casar nomes que vêm de fontes diferentes.
 *
 * A faixa de combinantes vai escrita por ponto de código, e não com os
 * caracteres literais: escritos direto no fonte eles atravessam editor, shell
 * e ferramenta de geração, e basta um deles não preservar para a função virar
 * identidade em silêncio — e silenciosamente, porque o resultado continua
 * sendo uma string plausível.
 *
 * Não houve defeito aqui: a versão com literais funcionava. A escrita
 * explícita é para que continue funcionando depois de passar por um editor
 * que normalize o arquivo de outro jeito.
 */
function semAcento(s: string) {
  return s.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toUpperCase();
}

/**
 * Busca sobre os 5.571 municípios, não sobre os 474 com emenda.
 *
 * <h2>Por que todos, e não só os que têm dado</h2>
 *
 * Buscar só entre os 474 ensinaria ao leitor que "minha cidade não está no
 * site" — quando a verdade é que a fonte não diz nada sobre ela. É
 * exatamente a mensagem que a tela de cidade sem registro existe para dar, e
 * ela ficaria inalcançável justamente para quem mais precisa dela: nove em
 * cada dez municípios.
 *
 * <h2>A lista é carregada sob demanda</h2>
 *
 * 164 KB (cerca de 40 comprimidos), buscados só por quem abre esta página.
 * Embuti-los no bundle cobraria o custo de todo visitante, inclusive de quem
 * nunca usa a busca.
 */
export function BuscaDeMunicipios() {
  const [todos, setTodos] = useState<Municipio[] | null>(null);
  const [comEmenda, setComEmenda] = useState<{ uf: string; municipio: string }[]>([]);
  const [erro, setErro] = useState(false);
  const [termo, setTermo] = useState("");

  useEffect(() => {
    let vivo = true;
    fetch("/municipios.json")
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((d: Municipio[]) => { if (vivo) setTodos(d); })
      .catch(() => { if (vivo) setErro(true); });
    return () => { vivo = false; };
  }, []);

  /*
   * A marcacao e buscada AQUI, no navegador, e nao passada pelo servidor.
   *
   * Na primeira versao ela vinha como prop do componente de pagina, resolvida
   * no build. Em 29/09/2026 essa chamada falhou em silencio -- o `catch` do
   * cliente devolveu a fixture -- e a pagina foi publicada conhecendo UMA
   * cidade de 1.596. A busca marcou tudo como "sem registro na fonte",
   * inclusive cidades que tinham emenda.
   *
   * Nada acusou: o build gerou as 1.596 paginas normalmente, porque a OUTRA
   * chamada a mesma funcao funcionou. Dois resultados diferentes no mesmo
   * build, e o errado saiu publicado.
   *
   * Buscado em tempo de execucao, o dado ainda fica fresco entre publicacoes
   * -- ele cresce a cada ano carregado -- e a falha some sozinha na proxima
   * visita, em vez de congelar num HTML por dias.
   */
  useEffect(() => {
    let vivo = true;
    const base = process.env.NEXT_PUBLIC_API_URL;
    if (!base) return;
    fetch(`${base}/emendas/municipios`)
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((d: { uf: string; municipio: string }[]) => { if (vivo) setComEmenda(d); })
      // Falha aqui NAO marca nada como "sem registro": marcar errado e pior
      // que nao marcar, porque afirma ausencia que pode nao existir.
      .catch(() => {});
    return () => { vivo = false; };
  }, []);

  const comDado = useMemo(
    () => new Set(comEmenda.map((m) => `${m.uf}/${semAcento(m.municipio)}`)),
    [comEmenda],
  );
  const sabemosQuemTem = comEmenda.length > 0;

  const resultados = useMemo(() => {
    if (!todos) return [];
    const alvo = semAcento(termo.trim());
    if (alvo.length < 2) return [];
    return todos.filter(([nome]) => semAcento(nome).includes(alvo)).slice(0, 40);
  }, [todos, termo]);

  return (
    <div className="mt-6">
      <label htmlFor="busca-municipio" className="sr-only">
        Nome do município
      </label>
      <input
        id="busca-municipio"
        type="search"
        value={termo}
        onChange={(e) => setTermo(e.target.value)}
        placeholder="Nome do município"
        autoComplete="off"
        className="w-full rounded-padrao border border-borda-forte bg-superficie px-3 py-2
                   text-texto placeholder:text-texto-tenue focus:border-foco focus:outline-none"
      />

      {erro && (
        <p className="mt-4 text-sm text-texto-suave">
          Não foi possível carregar a lista de municípios. Recarregue a página.
        </p>
      )}

      {!erro && termo.trim().length >= 2 && resultados.length === 0 && todos && (
        <p className="mt-4 text-sm text-texto-suave">
          Nenhum município com esse nome.
        </p>
      )}

      {resultados.length > 0 && (
        <ul className="mt-4 divide-y divide-borda">
          {resultados.map(([nome, uf, ibge]) => {
            const temDado = comDado.has(`${uf}/${semAcento(nome)}`);
            return (
              <li key={ibge} className="py-2">
                <Link
                  href={`/municipios/${uf}/${encodeURIComponent(nome.toUpperCase())}/`}
                  className="flex items-baseline justify-between gap-3 text-acento hover:underline"
                >
                  <span>
                    {nome} <span className="text-texto-tenue">— {uf}</span>
                  </span>
                  {/*
                    Marcar quem tem dado evita beco: a pessoa escolhe saber
                    antes de clicar. Nao marcar seria esconder a lacuna
                    justamente no momento da escolha.
                  */}
                  {/* Sem a lista, nao afirma nada -- ver o efeito acima. */}
                  {!sabemosQuemTem ? null : temDado ? (
                    <span className="shrink-0 text-xs text-texto-suave">
                      com emenda registrada
                    </span>
                  ) : (
                    <span className="shrink-0 text-xs text-texto-tenue">
                      sem registro na fonte
                    </span>
                  )}
                </Link>
              </li>
            );
          })}
        </ul>
      )}

      <p className="mt-6 max-w-prose text-xs text-texto-tenue">
        A lista traz os <strong>5.571 municípios</strong> do país, não só os que
        têm emenda registrada. Cidade sem registro não significa que não recebeu
        — significa que a fonte não diz para onde o dinheiro foi, e isso vale
        para a maior parte dele.
      </p>
    </div>
  );
}
