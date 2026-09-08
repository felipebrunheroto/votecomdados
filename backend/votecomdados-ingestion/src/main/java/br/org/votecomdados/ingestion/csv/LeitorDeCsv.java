package br.org.votecomdados.ingestion.csv;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * CSV do padrão dos portais brasileiros de dados abertos: separador {@code ;},
 * todo campo entre aspas, cabeçalho na primeira linha.
 *
 * <p>Nasceu dentro do leitor do TSE e saiu de lá quando o cadastro de
 * deputados da Câmara passou a precisar do mesmo parser. Duplicar não era
 * opção: as regras que parecem detalhe — o BOM, a aspa dobrada dentro de campo
 * citado, o {@code ;} que não separa quando está entre aspas — são exatamente
 * as que, erradas, corrompem em silêncio em vez de estourar.
 *
 * <p>Feito à mão em vez de com biblioteca porque o formato é rígido e
 * conhecido, e uma dependência a mais aqui ampliaria a superfície de módulos
 * que lidam com dado pessoal.
 */
@Component
public class LeitorDeCsv {

    private final ObjectMapper json;

    // Publico: o parser nao tem estado nem invariante a proteger, e ha teste
    // que o constroi fora do contexto do Spring -- subir a aplicacao inteira,
    // com banco, para exercitar leitura de CSV seria caro sem motivo.
    public LeitorDeCsv(ObjectMapper json) {
        this.json = json;
    }

    /** Lê um arquivo solto, detectando a codificação. */
    public List<JsonNode> ler(Path csv) {
        try (BufferedReader r = Files.newBufferedReader(csv, codificacaoDe(csv))) {
            var linhas = new ArrayList<JsonNode>();
            ler(r, linhas);
            return linhas;
        } catch (IOException e) {
            throw new IllegalStateException("falha ao ler " + csv, e);
        }
    }

    /**
     * Lê de um leitor já aberto — é por aqui que entra o conteúdo de dentro de
     * um zip, onde quem abre a entrada é que sabe a codificação.
     */
    public void ler(BufferedReader r, List<JsonNode> destino) throws IOException {
        String cabecalho = r.readLine();
        if (cabecalho == null) return;
        List<String> colunas = separar(cabecalho.replace("﻿", ""));

        String linha;
        while ((linha = r.readLine()) != null) {
            if (linha.isBlank()) continue;
            List<String> valores = separar(linha);
            ObjectNode no = json.createObjectNode();
            for (int i = 0; i < colunas.size(); i++) {
                no.put(colunas.get(i), i < valores.size() ? valores.get(i) : null);
            }
            destino.add(no);
        }
    }

    /** Separador {@code ;} com todo campo entre aspas. */
    public static List<String> separar(String linha) {
        var campos = new ArrayList<String>();
        var atual = new StringBuilder();
        boolean dentroDeAspas = false;
        for (int i = 0; i < linha.length(); i++) {
            char c = linha.charAt(i);
            if (c == '"') {
                // "" dentro de campo citado é uma aspa literal.
                if (dentroDeAspas && i + 1 < linha.length() && linha.charAt(i + 1) == '"') {
                    atual.append('"');
                    i++;
                } else {
                    dentroDeAspas = !dentroDeAspas;
                }
            } else if (c == ';' && !dentroDeAspas) {
                campos.add(atual.toString());
                atual.setLength(0);
            } else {
                atual.append(c);
            }
        }
        campos.add(atual.toString());
        return campos;
    }

    /**
     * As amostras do repositório são gravadas em UTF-8 (para serem legíveis em
     * diff e no navegador); o pacote do TSE é latin-1. Detectar em vez de
     * exigir evita que a diferença vire um erro sem relação aparente.
     */
    public static Charset codificacaoDe(Path csv) throws IOException {
        byte[] amostra = Files.readAllBytes(csv);
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(amostra));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException naoEhUtf8) {
            return StandardCharsets.ISO_8859_1;
        }
    }
}
