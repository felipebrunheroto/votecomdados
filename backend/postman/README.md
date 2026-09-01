# Coleção Postman — API local

`VoteComDados.postman_collection.json` cobre os 23 cenários da API, com
asserções que verificam o contrato de [`../../docs/API.md`](../../docs/API.md) —
não apenas o código HTTP. Rodar a coleção inteira funciona como teste de fumaça.

## Usar no VS Code

1. Instale a extensão **Postman** (`Postman.postman-for-vscode`).
2. Abra o ícone do Postman na barra lateral e faça login (a extensão exige conta).
3. Em **Collections**, use *Import* e selecione:
   - `VoteComDados.postman_collection.json`
   - `local.postman_environment.json` (opcional — a coleção já traz `baseUrl`
     com valor padrão)
4. Suba a API antes de disparar as requisições:

   ```bash
   cd backend
   docker compose up
   ```

   Espere `GET {{baseUrl}}/actuator/health/readiness` responder `{"status":"UP"}`
   — é a primeira requisição da pasta **0. Saúde**.

5. Para rodar tudo de uma vez: clique com o botão direito na coleção →
   **Run collection**.

## Organização

| Pasta | Cobre |
|---|---|
| 0. Saúde | `readiness` e `liveness` do Actuator |
| 1. Busca de candidatos | Listagem, busca textual sem acento, filtros de cargo/UF, filtro de mandato anterior |
| 2. Perfil do candidato | Trajetória nos três níveis, cobertura por esfera, candidato sem mandato, candidato de outro estado, projetos e votações |
| 3. Detalhes | Proposição com autoria completa, votação nominal com placar, simbólica sem placar, votação em comissão, votação secreta do Senado |
| 4. Metadados | Frescor por fonte, incluindo o caso de sincronização com falha |
| 5. Erros e limites | 404, teto de `pageSize`, tamanho de `q`, valor de enum inválido |

Os ids em **Variables** correspondem ao seed do perfil `dev`, e cada um foi
escolhido por exercitar um caso difícil — candidato sem mandato, registro
indeferido, mandato anterior a 2001, atuação estadual fora do escopo, votação
secreta do Senado.

**Adriana Ventura** (`idCandidatoComAtuacao`) é quem carrega os casos mais
recentes: ela tem voto de Obstrução e Ausência (Câmara), voto de comissão
normal e voto em separado (Alesp), e participação em votação secreta
(Senado) — as quatro fontes, num perfil só. O teste `Votações` cobre os
quatro na mesma requisição.

## Se a importação falhar

O Postman recusa qualquer problema com a mesma mensagem genérica — *"Could not
import collection. Please try again."* — sem dizer o motivo, então a única
saída é isolar por eliminação.

**Primeiro, confirme que o arquivo é válido:**

```bash
cd backend/postman
npm install    # uma vez
npm run validar
```

São três verificações independentes: o **schema oficial v2.1** (versionado em
`schema-v2.1.0.json`, roda offline), a carga pela biblioteca
`postman-collection` — a mesma que o Postman usa internamente — e a sanidade do
conteúdo (UUID, URL resolvível, asserções). Se as três passam, o arquivo não é
o problema.

**Depois, isole a extensão:** importe `teste-minimo.postman_collection.json`.
É uma coleção de uma requisição só, sem acentos, no formato mais simples que o
schema aceita (`npm run validar-minimo` confirma que ela é válida).

| Resultado | Conclusão |
|---|---|
| O mínimo também falha | O problema é a extensão, não o arquivo — veja abaixo |
| O mínimo importa, o completo não | Aí sim é o conteúdo; abra uma issue com o passo que falhou |

**Quando é a extensão**, na ordem do mais provável:

1. **Sessão expirada.** A extensão exige conta e falha na importação — não no
   login — quando o token venceu. Saia e entre de novo.
2. **Nenhum workspace selecionado.** A importação precisa de um destino; se a
   lista de workspaces não carregou, ela falha sem explicar.
3. **Offline ou atrás de proxy.** A importação passa pelo backend do Postman,
   não é local. Sem rede, falha.
4. **Cache corrompido.** Feche o VS Code, apague `~/.postman-vscode` (ou
   `Postman: Clear Cache` na paleta de comandos) e reabra.

Se nada disso resolver, não insista: use o `endpoints.http` ou a linha de
comando abaixo. Ambos exercitam exatamente os mesmos endpoints e nenhum dos
dois depende de conta no Postman.

## Alternativa sem Postman: `endpoints.http`

`endpoints.http` cobre os mesmos 23 endpoints pela extensão **REST Client**
(`humao.rest-client`), que não exige login nem importação: abra o arquivo e
clique em **Send Request** acima de qualquer requisição. As variáveis (`baseUrl`,
ids do seed) ficam no topo do arquivo.

Ele é gerado a partir da coleção, para os dois não divergirem:

```bash
npm run http
```

O que ele não faz: as asserções não vêm junto. Para verificar o contrato, e não
só olhar a resposta, use `npm run rodar`.

## Rodar pela linha de comando

A mesma coleção roda sem a extensão:

```bash
cd backend/postman
npm run rodar
```

Útil no CI: o processo sai com código diferente de zero se alguma asserção
falhar. Foi assim que se descobriu que `cargo=IMPERADOR` devolvia 500 em vez
de 400 — hoje coberto também por teste de integração no backend.

## Scripts

| Comando | O que faz |
|---|---|
| `npm run validar` | Schema v2.1 + carga pela lib do Postman + sanidade da coleção |
| `npm run validar-minimo` | O mesmo, no arquivo de diagnóstico |
| `npm run rodar` | Executa os 23 cenários com Newman e falha o processo se alguma asserção quebrar |
| `npm run http` | Regenera `endpoints.http` a partir da coleção |
