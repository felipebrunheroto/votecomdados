import type { Metadata } from "next";
import { FrescorDosDados } from "@/componentes/dominio/FrescorDosDados";
import { LinkFonteOficial } from "@/componentes/dominio/LinkFonteOficial";

export const metadata: Metadata = {
  title: "Sobre os dados",
  description:
    "De onde vêm os dados, o que a plataforma cobre, o que não cobre, e como interpretar cada tipo de voto.",
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

export default function PaginaSobre() {
  return (
    <article className="space-y-10">
      <header>
        <h1 className="text-2xl font-semibold tracking-tight text-texto">
          Sobre os dados
        </h1>
        <p className="mt-2 max-w-prose text-texto-suave">
          Esta página explica de onde vem cada informação, o que a plataforma
          cobre, o que ela <strong className="font-semibold text-texto">não</strong>{" "}
          cobre, e como interpretar o que você vê. Ler antes de comparar dois
          candidatos evita a conclusão errada mais comum aqui.
        </p>
      </header>

      <Secao id="s-escopo" titulo="Quem está na base">
        <p className="max-w-prose text-texto-suave">
          Apenas pessoas com registro de candidatura na eleição de{" "}
          <strong className="font-semibold text-texto">2026</strong>, para
          qualquer cargo. Quem não é candidato não tem página aqui — inclusive
          parlamentares em exercício que não estão concorrendo. Isso é
          deliberado: a plataforma existe para ajudar na decisão de voto de
          2026, e manter registro de quem não está em disputa seria acumular
          dado pessoal sem finalidade.
        </p>
        <p className="max-w-prose text-texto-suave">
          Candidatos com registro indeferido ou sub judice continuam visíveis,
          com o status exibido. Omiti-los enquanto a Justiça Eleitoral decide
          seria esconder do eleitor uma candidatura que ainda pode se confirmar.
        </p>
      </Secao>

      <Secao id="s-fontes" titulo="De onde vêm os dados">
        <ul className="max-w-prose space-y-3 text-texto-suave">
          <li>
            <strong className="font-medium text-texto">Trajetória eleitoral</strong> —
            dados abertos do TSE, cobrindo eleições municipais, estaduais e
            federais. É a única fonte uniforme para os três níveis, e por isso a
            trajetória é completa mesmo quando a atuação legislativa não é.{" "}
            <LinkFonteOficial href="https://dadosabertos.tse.jus.br/">
              Portal do TSE
            </LinkFonteOficial>
          </li>
          <li>
            <strong className="font-medium text-texto">Atuação federal</strong> —
            dados abertos da Câmara dos Deputados e do Senado Federal:
            proposições, autoria e votos nominais em plenário.{" "}
            <LinkFonteOficial href="https://dadosabertos.camara.leg.br/">
              Portal da Câmara
            </LinkFonteOficial>
          </li>
          <li>
            <strong className="font-medium text-texto">Atuação estadual em São Paulo</strong> —
            dados abertos da Alesp: proposituras, autoria e votos individuais em
            comissões.{" "}
            <LinkFonteOficial href="https://www.al.sp.gov.br/dados-abertos/">
              Portal da Alesp
            </LinkFonteOficial>
          </li>
        </ul>
      </Secao>

      <Secao id="s-cobertura" titulo="O que cobrimos — e o que não">
        <p className="max-w-prose text-texto-suave">
          A cobertura é desigual, e a desigualdade é da{" "}
          <em>publicação de dados</em>, não da atuação das pessoas.
        </p>

        <div className="overflow-x-auto">
          <table className="w-full min-w-[34rem] text-sm">
            <caption className="sr-only">
              Cobertura por nível de governo e tipo de dado
            </caption>
            <thead>
              <tr className="border-b border-borda text-left text-texto-suave">
                <th scope="col" className="py-2 font-medium">Nível</th>
                <th scope="col" className="py-2 font-medium">Trajetória</th>
                <th scope="col" className="py-2 font-medium">Projetos</th>
                <th scope="col" className="py-2 font-medium">Votos em plenário</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-borda text-texto">
              <tr>
                <th scope="row" className="py-2 text-left font-normal">Federal</th>
                <td className="py-2">Sim</td>
                <td className="py-2">Sim, desde 1934</td>
                <td className="py-2">Sim, desde 2001</td>
              </tr>
              <tr>
                <th scope="row" className="py-2 text-left font-normal">Estadual — São Paulo</th>
                <td className="py-2">Sim</td>
                <td className="py-2">Sim, desde 1970</td>
                <td className="py-2 text-texto-suave">Só em PDF; comissão desde 2006</td>
              </tr>
              <tr>
                <th scope="row" className="py-2 text-left font-normal">Estadual — demais</th>
                <td className="py-2">Sim</td>
                <td className="py-2 text-texto-suave">Ainda não cobrimos</td>
                <td className="py-2 text-texto-suave">Ainda não cobrimos</td>
              </tr>
              <tr>
                <th scope="row" className="py-2 text-left font-normal">Municipal</th>
                <td className="py-2">Sim</td>
                <td className="py-2 text-texto-suave">Ainda não cobrimos</td>
                <td className="py-2 text-texto-suave">Ainda não cobrimos</td>
              </tr>
            </tbody>
          </table>
        </div>

        <p className="max-w-prose text-texto-suave">
          <strong className="font-semibold text-texto">
            &ldquo;A fonte não publica&rdquo; e &ldquo;ainda não cobrimos&rdquo; são
            coisas diferentes
          </strong>{" "}
          e a plataforma nunca troca uma pela outra. No primeiro caso nenhuma
          engenharia resolve: a Casa simplesmente não divulga o dado. No
          segundo, é trabalho nosso ainda não feito.
        </p>
        <p className="max-w-prose text-texto-suave">
          Antes de 2001 a Câmara não publica o voto individual de cada
          parlamentar. Quem teve mandato nos anos 90 aparece com projetos e sem
          votações — a lacuna é da fonte. No Senado o registro alcança 1991, dez
          anos a mais.
        </p>
        <p className="max-w-prose text-texto-suave">
          A Alesp é um caso à parte, e vale ser preciso:{" "}
          <strong className="font-semibold text-texto">
            ela publica, sim, as votações de plenário
          </strong>{" "}
          — mas só como PDF, um por votação, com o painel digitalizado como
          imagem. O registro existe e não pode ser lido por programa, então não
          entra aqui. O que entra é o voto em comissão, que ela publica de forma
          estruturada desde 2006 — e que aparece sempre marcado como tal, porque
          não tem o mesmo peso de uma deliberação de plenário.
        </p>
      </Secao>

      <Secao id="s-comparar" titulo="Por que comparar candidatos exige cuidado">
        <p className="max-w-prose text-texto-suave">
          Um vereador com dez anos de mandato aparece aqui com trajetória rica e{" "}
          <strong className="font-semibold text-texto">nenhum projeto</strong>,
          porque as câmaras municipais não publicam dados legislativos em
          formato aberto. Um deputado paulista aparece com mais informação que
          um mineiro, porque só a Alesp está integrada.
        </p>
        <p className="max-w-prose text-texto-suave">
          Em nenhum dos dois casos isso significa que a pessoa fez menos.
          Comparar candidatos de níveis ou estados diferentes sem esse contexto
          é comparar o quanto cada Casa legislativa publica — não o que cada
          pessoa fez. Por isso todo perfil traz, no rodapé, o que está e o que
          não está coberto naquele caso específico.
        </p>
      </Secao>

      <Secao id="s-votos" titulo="Como ler cada tipo de voto">
        <dl className="max-w-prose space-y-4">
          {[
            ["Sim / Não", "Posição registrada sobre a matéria em votação nominal."],
            ["Abstenção", "O parlamentar estava presente e optou por não se posicionar."],
            ["Obstrução", "Manobra regimental de orientação de bancada, usada para atrasar ou impedir a votação. Não é uma posição sobre o mérito da matéria."],
            ["Ausente", "Não compareceu. Na Câmara essa linha é cálculo nosso, não registro da Casa: a fonte publica só quem votou, e a ausência sai do cruzamento com quem estava em exercício naquele dia. Ela aparece marcada como apurada pela plataforma."],
            ["Licenciado", "Estava em licença, e por isso não votou. Separar da ausência importa: licença médica ou parental não é falta."],
            ["Ausência justificada", "Ausência por missão oficial ou atividade parlamentar — trabalho da Casa, e não falta."],
            ["Presente, não votou", "Estava na sessão e não registrou voto. Também não é falta."],
            ["Votação secreta", "Participou de uma deliberação secreta: a Casa registra quem votou, não como cada um votou. São 53% das votações de plenário do Senado — ler isso como omissão inverteria o sentido do fato."],
            ["Em branco", "Voto em branco, que a Alesp conta separado da abstenção."],
            ["Voto em separado", "O parlamentar votou apresentando por escrito um parecer divergente do relator. É o oposto de se abster — e a fonte não informa se o divergente era favorável ou contrário ao projeto."],
            ["Artigo 17", "O presidente da Casa só vota nas situações previstas no regimento."],
            ["Votação simbólica", "A Casa aprova sem registrar o voto de cada parlamentar. Nenhum nome pode ser associado a um voto nessas matérias — a ausência do dado é da origem."],
            ["Voto em comissão", "Deliberação em comissão permanente, não em plenário. Tem peso político diferente e por isso aparece sempre marcado como tal."],
          ].map(([termo, definicao]) => (
            <div key={termo}>
              <dt className="font-medium text-texto">{termo}</dt>
              <dd className="text-texto-suave">{definicao}</dd>
            </div>
          ))}
        </dl>
        <p className="max-w-prose text-sm text-texto-suave">
          A plataforma sempre exibe o rótulo <em>original</em> da fonte ao lado
          da nossa categorização. A categorização é interpretação nossa; o
          rótulo é o fato. A Alesp, por exemplo, descreve seus votos com quase
          500 frases diferentes — de &ldquo;Favorável ao parecer&rdquo; a
          &ldquo;Favorável ao projeto e contrário ao parecer&rdquo; —, e
          achatá-las todas em duas categorias esconderia distinções reais.
        </p>
      </Secao>

      <Secao id="s-identidade" titulo="Como um candidato é ligado ao seu histórico">
        <p className="max-w-prose text-texto-suave">
          TSE, Câmara, Senado e Alesp usam identificadores diferentes para a
          mesma pessoa. Quando há uma chave comum, o vínculo é automático.
          Quando não há, comparamos nome, estado e partido — e um vínculo com
          baixa confiança <strong className="font-semibold text-texto">não é
          exibido</strong> até passar por revisão humana.
        </p>
        <p className="max-w-prose text-texto-suave">
          A consequência é deliberada: preferimos mostrar um perfil incompleto a
          atribuir a alguém um voto que não foi dele. Dois homônimos de estados
          diferentes seriam o erro mais fácil de cometer, e o mais grave.
        </p>
      </Secao>

      <Secao id="s-neutralidade" titulo="O que a plataforma não faz">
        <ul className="max-w-prose list-disc space-y-2 pl-5 text-texto-suave">
          <li>Não atribui nota, ranking ou score a candidatos.</li>
          <li>Não classifica projetos como bons ou ruins.</li>
          <li>Não infere posição ideológica a partir de votos.</li>
          <li>Não recomenda voto.</li>
        </ul>
        <p className="max-w-prose text-texto-suave">
          Cada matéria e votação exibida traz o link para o registro oficial. Se
          algo aqui divergir da fonte, a fonte prevalece — e queremos saber.
        </p>
      </Secao>

      <Secao id="s-frescor" titulo="Quando os dados foram atualizados">
        <p className="max-w-prose text-texto-suave">
          A sincronização roda diariamente. As datas abaixo são consultadas no
          momento em que você abre esta página, e não a data em que o site foi
          publicado.
        </p>
        <FrescorDosDados />
      </Secao>
    </article>
  );
}
