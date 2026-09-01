package br.org.votecomdados.ingestion.coorte;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Abre o {@code consulta_cand_AAAA.zip} do TSE e entrega uma linha por vez.
 *
 * <h2>Três coisas verificadas no arquivo real (31/08/2026)</h2>
 *
 * <ol>
 *   <li><b>A codificação é latin-1, não UTF-8.</b> Ler como UTF-8 estoura em
 *       {@code Ç} — e, com decodificação tolerante, corromperia silenciosamente
 *       todo nome acentuado do país.</li>
 *   <li><b>{@code _BRASIL.csv} é a união exata dos arquivos por UF</b> —
 *       conferido: 20.809 candidaturas dos dois lados, nenhuma exclusiva.
 *       Processar os dois dobraria o trabalho sem acrescentar uma linha.</li>
 *   <li><b>O zip traz um {@code leiame.pdf}</b> junto dos CSVs. Iterar entradas
 *       sem filtrar extensão faria o parser tentar ler um PDF como CSV.</li>
 * </ol>
 */
@Component
public class LeitorDeArquivoTse {

    private static final Logger log = LoggerFactory.getLogger(LeitorDeArquivoTse.class);

    /** Verificado no arquivo: acentuação em ISO-8859-1. */
    private static final Charset CODIFICACAO = Charset.forName("ISO-8859-1");

    private final ObjectMapper json;

    LeitorDeArquivoTse(ObjectMapper json) {
        this.json = json;
    }

    /**
     * Lê o zip inteiro, pulando o consolidado nacional e o leia-me.
     *
     * <p>Devolve lista, não fluxo preguiçoso: são ~21 mil linhas por eleição
     * geral, e a simplicidade de fechar o zip logo vale mais do que a memória
     * economizada. Se um dia a coorte incluir eleição municipal — onde o número
     * passa de 400 mil — vale reconsiderar.
     */
    public List<JsonNode> ler(Path zip) {
        var linhas = new ArrayList<JsonNode>();
        try (ZipFile arquivo = new ZipFile(zip.toFile())) {
            var entradas = arquivo.stream()
                .filter(e -> !e.isDirectory())
                .filter(e -> e.getName().toLowerCase().endsWith(".csv"))
                // O consolidado nacional é a união exata das UFs: incluí-lo
                // processaria tudo duas vezes.
                .filter(e -> !e.getName().toUpperCase().endsWith("_BRASIL.CSV"))
                .toList();

            for (ZipEntry entrada : entradas) {
                int antes = linhas.size();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        arquivo.getInputStream(entrada), CODIFICACAO))) {
                    lerCsv(r, linhas);
                }
                log.debug("{}: {} candidatura(s)", entrada.getName(), linhas.size() - antes);
            }
            log.info("TSE {}: {} candidatura(s) em {} arquivo(s) por UF",
                     zip.getFileName(), linhas.size(), entradas.size());
            return linhas;
        } catch (IOException e) {
            throw new IllegalStateException("falha ao ler " + zip, e);
        }
    }

    private void lerCsv(BufferedReader r, List<JsonNode> destino) throws IOException {
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

    /**
     * Separador {@code ;} com todo campo entre aspas.
     *
     * <p>Feito à mão em vez de com biblioteca porque o formato é rígido e
     * conhecido, e uma dependência a mais aqui só ampliaria a superfície de um
     * módulo que já lida com dado pessoal.
     */
    private static List<String> separar(String linha) {
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
     * Lê um CSV solto, fora do zip.
     *
     * <p>Existe para dois usos legítimos: reprocessar uma UF isolada sem
     * rebaixar o pacote inteiro, e testar contra a amostra fixada em
     * {@code db/golden/} — que é CSV justamente para poder ser lida em diff.
     *
     * <p>A codificação é a mesma do zip: quem exporta uma UF do pacote leva o
     * latin-1 junto.
     */
    public List<JsonNode> lerCsv(Path csv) {
        var linhas = new ArrayList<JsonNode>();
        try (BufferedReader r = java.nio.file.Files.newBufferedReader(csv, codificacaoDe(csv))) {
            lerCsv(r, linhas);
            return linhas;
        } catch (IOException e) {
            throw new IllegalStateException("falha ao ler " + csv, e);
        }
    }

    /**
     * A amostra do repositório é gravada em UTF-8 (para ser legível em diff e
     * no navegador); o arquivo da fonte é latin-1. Detectar em vez de exigir
     * evita que a diferença vire um erro sem relação aparente.
     */
    private static Charset codificacaoDe(Path csv) throws IOException {
        byte[] amostra = java.nio.file.Files.readAllBytes(csv);
        try {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(amostra));
            return java.nio.charset.StandardCharsets.UTF_8;
        } catch (java.nio.charset.CharacterCodingException naoEhUtf8) {
            return CODIFICACAO;
        }
    }

    /** Conveniência para quem consome em laço. */
    public Iterator<JsonNode> iterar(Path zip) {
        return ler(zip).iterator();
    }
}
