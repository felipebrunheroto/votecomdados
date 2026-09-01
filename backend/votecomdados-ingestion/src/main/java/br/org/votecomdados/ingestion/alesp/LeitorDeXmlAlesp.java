package br.org.votecomdados.ingestion.alesp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lê os XML da Alesp em fluxo, um registro por vez.
 *
 * <h2>Por que streaming não é otimização prematura aqui</h2>
 *
 * Os arquivos da Alesp são publicados inteiros, sem recorte por ano:
 * proposituras tem <b>132 MB</b> descompactado, autoria <b>144 MB</b>, votos de
 * comissão <b>67 MB</b>. Carregar qualquer um em árvore DOM custaria vários
 * gigabytes de heap num worker que roda em contêiner pequeno.
 *
 * <h2>Os registros são planos, e o leitor se aproveita disso</h2>
 *
 * Todo registro da Alesp é um elemento com filhos de texto — não há
 * aninhamento, ao contrário do Senado. O leitor desce exatamente um nível e
 * devolve um {@link ObjectNode} chato, que é o que a allowlist e o staging
 * esperam.
 *
 * <h2>Uma armadilha de nome</h2>
 *
 * {@code <Deputado>} é elemento de REGISTRO em {@code deputados.xml} e campo de
 * TEXTO dentro de cada voto. Por isso o nome do elemento de registro é
 * parâmetro, e não constante: quem chama declara o que está lendo.
 */
@Component
public class LeitorDeXmlAlesp {

    private static final XMLInputFactory FABRICA = criarFabrica();

    private static XMLInputFactory criarFabrica() {
        var f = XMLInputFactory.newInstance();
        // Arquivo de terceiro: sem entidades externas e sem expansão de DTD.
        // A fonte é pública e confiável, mas "confiável" não é controle.
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_COALESCING, true);
        return f;
    }

    /** Lê um XML solto. */
    public Fluxo ler(Path arquivo, String elementoDeRegistro) {
        try {
            return new Fluxo(Files.newInputStream(arquivo), elementoDeRegistro);
        } catch (IOException e) {
            throw new IllegalStateException("nao consigo abrir " + arquivo, e);
        }
    }

    /**
     * Lê o único XML de dentro de um zip.
     *
     * <p>{@code proposituras.zip} e {@code documento_autor.zip} trazem um
     * arquivo só. Se um dia trouxerem mais, é melhor falhar aqui do que ler o
     * primeiro e ignorar o resto em silêncio.
     */
    public Fluxo lerDoZip(Path zip, String elementoDeRegistro) {
        try {
            var entrada = new ZipInputStream(Files.newInputStream(zip));
            ZipEntry item;
            while ((item = entrada.getNextEntry()) != null) {
                if (!item.isDirectory() && item.getName().toLowerCase().endsWith(".xml")) {
                    return new Fluxo(entrada, elementoDeRegistro);
                }
            }
            entrada.close();
            throw new IllegalStateException("nenhum .xml dentro de " + zip);
        } catch (IOException e) {
            throw new IllegalStateException("nao consigo abrir " + zip, e);
        }
    }

    /** Iterável de uso único; fecha o arquivo ao terminar ou ao ser fechado. */
    public static final class Fluxo implements Iterable<ObjectNode>, AutoCloseable {

        private final InputStream origem;
        private final XMLStreamReader xml;
        private final String registro;

        private Fluxo(InputStream origem, String registro) {
            this.origem = origem;
            this.registro = registro;
            try {
                this.xml = FABRICA.createXMLStreamReader(origem);
            } catch (XMLStreamException e) {
                throw new IllegalStateException("XML invalido", e);
            }
        }

        @Override
        public Iterator<ObjectNode> iterator() {
            return new Iterator<>() {
                private ObjectNode proximo = avancar();

                @Override public boolean hasNext() {
                    return proximo != null;
                }

                @Override public ObjectNode next() {
                    if (proximo == null) throw new NoSuchElementException();
                    ObjectNode atual = proximo;
                    proximo = avancar();
                    return atual;
                }
            };
        }

        private ObjectNode avancar() {
            try {
                while (xml.hasNext()) {
                    if (xml.next() == XMLStreamConstants.START_ELEMENT
                        && registro.equals(xml.getLocalName())) {
                        return lerRegistro();
                    }
                }
                close();
                return null;
            } catch (XMLStreamException e) {
                throw new IllegalStateException("falha lendo " + registro, e);
            }
        }

        /**
         * Campo vazio vira ausente, não string vazia.
         *
         * <p>{@code CodOriginalidade} vem como 30 espaços em 42% das
         * proposituras. Guardar isso como valor faria um campo em branco
         * parecer preenchido no staging.
         */
        private ObjectNode lerRegistro() throws XMLStreamException {
            ObjectNode no = JsonNodeFactory.instance.objectNode();
            String campo = null;
            var valor = new StringBuilder();

            while (xml.hasNext()) {
                switch (xml.next()) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        campo = xml.getLocalName();
                        valor.setLength(0);
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (campo != null) valor.append(xml.getText());
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        if (registro.equals(xml.getLocalName())) return no;
                        if (campo != null && xml.getLocalName().equals(campo)) {
                            String texto = valor.toString().trim();
                            if (!texto.isEmpty()) no.put(campo, texto);
                            campo = null;
                        }
                    }
                    default -> { /* comentários, espaços entre registros */ }
                }
            }
            return no;
        }

        @Override
        public void close() {
            try {
                xml.close();
            } catch (XMLStreamException ignorado) {
                // fechar é melhor-esforço; o InputStream abaixo é o que importa
            }
            try {
                origem.close();
            } catch (IOException ignorado) {
                // idem
            }
        }
    }
}
