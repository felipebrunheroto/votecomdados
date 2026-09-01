import type { Metadata } from "next";
import Link from "next/link";
import { ManifestoDoPacote } from "@/componentes/dominio/ManifestoDoPacote";

export const metadata: Metadata = {
  title: "Dados abertos",
  description:
    "A base inteira da plataforma para baixar, conferir e reusar, sob licença CC BY 4.0 — com o que você precisa saber antes de usar.",
};

function Secao({
  id, titulo, children,
}: { id: string; titulo: string; children: React.ReactNode }) {
  return (
    <section aria-labelledby={id} className="space-y-3">
      <h2 id={id} className="text-lg font-semibold text-texto">{titulo}</h2>
      {children}
    </section>
  );
}

/** Os 12 arquivos do pacote, na ordem em que o exportador os gera. */
const ARQUIVOS: [string, string][] = [
  ["politico.csv", "As pessoas candidatas em 2026."],
  ["candidatura.csv", "Cada disputa eleitoral de cada pessoa, desde 1994."],
  ["identificador_externo.csv", "O cruzamento: qual pessoa é qual parlamentar em cada Casa, com o método e o score."],
  ["proposicao.csv", "Matérias legislativas apresentadas."],
  ["proposicao_tema.csv", "Temas de cada matéria, como a fonte os classifica."],
  ["proposicao_autor.csv", "Autoria, incluindo coautores que não são candidatos."],
  ["votacao.csv", "Cada votação, com casa, âmbito, data e se era secreta."],
  ["voto_nominal.csv", "O voto individual, com o rótulo original da fonte ao lado da nossa categoria."],
  ["mandato_exercicio.csv", "Quem estava em exercício em cada período."],
  ["mapeamento_voto.csv", "O que decidimos que cada rótulo de voto significa."],
  ["mapeamento_situacao.csv", "O mesmo, para as situações de mandato."],
  ["cobertura_fonte.csv", "Até onde cada fonte vai, por Casa e por recurso."],
];

export default function PaginaDadosAbertos() {
  return (
    <article className="space-y-10">
      <header>
        <h1 className="text-2xl font-semibold tracking-tight text-texto">
          Dados abertos
        </h1>
        <p className="mt-2 max-w-prose text-texto-suave">
          Toda a base desta plataforma está disponível para download, em CSV,
          sob licença{" "}
          <a
            href="https://creativecommons.org/licenses/by/4.0/deed.pt-br"
            className="underline underline-offset-2 hover:text-texto"
            rel="license noopener noreferrer"
            target="_blank"
          >
            CC BY 4.0
          </a>
          . Você pode baixar, republicar, cruzar com outras bases e{" "}
          <strong className="font-semibold text-texto">apontar nossos erros</strong>.
        </p>
      </header>

      {/* A justificativa vem antes do botão de download de propósito: o motivo
          de o pacote existir é mais importante que o pacote. */}
      <Secao id="s-porque" titulo="Por que publicamos isto">
        <p className="max-w-prose text-texto-suave">
          Esta plataforma faz uma afirmação que você não teria como conferir por
          fora:{" "}
          <em>&ldquo;este deputado é esta candidata&rdquo;</em>. O TSE e as Casas
          legislativas não usam um identificador comum para a mesma pessoa, então
          todo vínculo entre uma candidatura e um histórico de votos é{" "}
          <strong className="font-semibold text-texto">interpretação nossa</strong>.
        </p>
        <p className="max-w-prose text-texto-suave">
          Uma plataforma de transparência que não pode ser auditada está pedindo
          fé, não mostrando dado. Publicar a base curada inverte isso: quem
          discordar de um vínculo pode baixar o arquivo, refazer o cruzamento e
          mostrar onde erramos.
        </p>
      </Secao>

      <Secao id="s-pacote" titulo="O pacote publicado">
        <ManifestoDoPacote />
        <p>
          <a
            href="/dados-abertos/latest/"
            className="inline-block rounded-padrao bg-acento px-4 py-2 font-medium text-acento-contraste hover:opacity-90"
          >
            Abrir o pacote mais recente
          </a>
        </p>
      </Secao>

      <Secao id="s-antes" titulo="O que ler antes de usar">
        <p className="max-w-prose text-texto-suave">
          Estes cinco avisos também estão no arquivo{" "}
          <code className="text-xs">LEIA-ME.md</code>, dentro do pacote. Eles não
          são formalidade: cada um corresponde a uma conclusão errada que os
          dados permitiriam tirar sem eles.
        </p>

        <ol className="max-w-prose list-decimal space-y-4 pl-5 text-texto-suave marker:text-texto-tenue">
          <li>
            <strong className="font-medium text-texto">
              O cruzamento é afirmação nossa, não das fontes.
            </strong>{" "}
            O arquivo <code className="text-xs">identificador_externo.csv</code>{" "}
            traz cada vínculo com o método (determinístico ou por semelhança de
            nome), o score e se passou por revisão humana. Comece por ele se
            quiser conferir o nosso trabalho.
          </li>
          <li>
            <strong className="font-medium text-texto">
              Ausência e licença, na Câmara, são cálculo nosso.
            </strong>{" "}
            A Casa publica apenas quem registrou voto. As linhas marcadas como{" "}
            <code className="text-xs">DERIVADO</code> vêm do cruzamento entre a
            votação e quem estava em exercício naquele dia — trate-as como
            interpretação, não como registro oficial. No Senado é o contrário: a
            Casa publica a bancada inteira, e lá nada é derivado.
          </li>
          <li>
            <strong className="font-medium text-texto">
              A cobertura é desigual, e isso distorce comparações.
            </strong>{" "}
            Voto nominal da Câmara existe desde 2001, o do Senado desde 1991; a
            Alesp publica voto de comissão desde 2006, e o de plenário só em
            PDF; câmaras municipais não publicam nada estruturado. Comparar dois
            candidatos sem ler{" "}
            <code className="text-xs">cobertura_fonte.csv</code> é comparar a
            transparência das Casas, não a atuação das pessoas.
          </li>
          <li>
            <strong className="font-medium text-texto">
              A base é a coorte de 2026.
            </strong>{" "}
            Só existe registro de quem é candidato nesta eleição. Não é uma base
            histórica completa de parlamentares, e não serve para essa pergunta.
          </li>
          <li>
            <strong className="font-medium text-texto">
              A tradução dos rótulos é editorial.
            </strong>{" "}
            <code className="text-xs">mapeamento_voto.csv</code> mostra o que
            decidimos que cada rótulo da fonte significa. Discordar dessa
            tradução é discordar de nós, não da fonte — e o rótulo original está
            preservado em cada voto, justamente para permitir refazê-la.
          </li>
        </ol>
      </Secao>

      <Secao id="s-arquivos" titulo="O que vem no pacote">
        <dl className="max-w-prose space-y-3">
          {ARQUIVOS.map(([arquivo, descricao]) => (
            <div key={arquivo}>
              <dt className="font-mono text-sm font-medium text-texto">{arquivo}</dt>
              <dd className="text-sm text-texto-suave">{descricao}</dd>
            </div>
          ))}
        </dl>
        <p className="max-w-prose text-texto-suave">
          Vão junto <code className="text-xs">manifesto.json</code>, com a data e
          a volumetria do instantâneo, e{" "}
          <code className="text-xs">LEIA-ME.md</code>, com a metodologia.
        </p>
      </Secao>

      <Secao id="s-fora" titulo="O que não está no pacote">
        <p className="max-w-prose text-texto-suave">
          CPF — nem em forma de hash —, a identificação de quem fez a curadoria,
          os dados brutos baixados das fontes, os registros em quarentena e os
          históricos internos de alteração.
        </p>
        <p className="max-w-prose text-texto-suave">
          Nada disso serve para auditar o cruzamento, que é o propósito da
          publicação, e incluí-los só ampliaria a exposição de dado pessoal.
        </p>
      </Secao>

      <Secao id="s-citar" titulo="Como citar">
        <p className="max-w-prose text-texto-suave">
          Cada instantâneo tem endereço próprio, com a data, e{" "}
          <strong className="font-semibold text-texto">nunca é sobrescrito</strong>.
          Se você citou um pacote, ele continuará exatamente como estava.
        </p>
        <p className="max-w-prose text-texto-suave">
          Por isso, cite sempre o endereço datado, e não{" "}
          <code className="text-xs">/dados-abertos/latest/</code>: o{" "}
          <code className="text-xs">latest</code> é conveniência para quem quer o
          mais recente, e muda embaixo de quem o citou — um arquivo que muda não
          serve de evidência.
        </p>
      </Secao>

      <Secao id="s-erro" titulo="Achou um erro?">
        <p className="max-w-prose text-texto-suave">
          Um vínculo errado é o pior defeito que esta plataforma pode ter:
          atribui a alguém o voto de outra pessoa. Reportá-lo é a contribuição
          mais útil que existe aqui — abra uma issue no repositório com o{" "}
          <code className="text-xs">politico_id</code> e o arquivo.
        </p>
        <p className="max-w-prose text-texto-suave">
          Se algo no pacote divergir da fonte oficial,{" "}
          <strong className="font-semibold text-texto">a fonte prevalece</strong>.
          Como cada informação é interpretada está explicado em{" "}
          <Link href="/sobre" className="underline underline-offset-2 hover:text-texto">
            Sobre os dados
          </Link>
          .
        </p>
      </Secao>
    </article>
  );
}
