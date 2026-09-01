# VoteComDados — backend

Java 21 + Spring Boot 4, multi-módulo Maven. Serviço HTTP somente leitura que
implementa o contrato de [`../docs/API.md`](../docs/API.md).

| Módulo | Papel |
|---|---|
| `votecomdados-core` | Modelo de domínio e migrations Flyway. Biblioteca, sem Spring Boot |
| `votecomdados-ingestion` | Worker de ingestão. Job em lote, sem porta HTTP: sobe, trabalha, morre |

```bash
# coorte a partir do pacote do TSE
java -jar worker.jar --job=COORTE --fonte=TSE --arquivo=consulta_cand_2026.zip
# ciclo diario da Camara (so trabalha se a fonte disser que mudou)
java -jar worker.jar --job=INCREMENTAL --fonte=CAMARA --ano=2026 \
     --dados-abertos=/publicar
```

Códigos de saída: `0` sucesso, `1` falha, `2` já havia execução viva para a
fonte (encerrar sem enfileirar não é sucesso nem falha).
| `votecomdados-api` | Serviço HTTP. Controllers, serviços, repositórios |

## Pré-requisitos

**Não é preciso instalar Java, Maven ou Node** — tudo roda em container.
Só Docker (com Compose v2) é necessário.

## Ver tudo rodando

Da **raiz do repositório** (não daqui de `backend/`):

```bash
docker compose up
```

Sobe três serviços — Postgres, API e o frontend Next.js — e conecta os três:

1. **`db`** — Postgres 16, schema aplicado pelo Flyway assim que sobe.
2. **`api`** — Spring Boot no perfil `dev`: além do schema, carrega dados de
   exemplo (seis candidatos com casos difíceis — sem mandato, registro
   indeferido, mandato anterior a 2001 — e votos das quatro fontes: Câmara,
   Senado, Alesp e a ausência derivada). Fica em `http://localhost:8080`.
3. **`web`** — o frontend, apontado para a API acima. Fica em
   `http://localhost:3000`.

A primeira subida baixa dependências (Maven e npm) e demora alguns minutos;
as seguintes reaproveitam os caches (`.m2-cache/` e o volume `web-node-modules`)
e sobem em segundos. Os fontes de `backend/` e `web/` são bind-mounted —
editar e salvar recarrega sozinho nos dois lados, sem rebuildar a imagem.

**Verificar que subiu:**

```bash
curl -s localhost:8080/actuator/health/readiness
# {"status":"UP"}
```

Depois abra **http://localhost:3000** — a busca já vem com os seis candidatos
de exemplo. Vale abrir o perfil de **Adriana Ventura**
(`/politicos/a1000000-0000-4000-8000-000000000001`), aba **Votações**: é o
único lugar que reúne, na mesma pessoa, um voto de cada fonte — Sim/Obstrução/
Ausência (Câmara), voto de comissão e voto em separado (Alesp), e participação
em votação secreta (Senado). A tabela completa de "o que vale a pena olhar"
está em [`../web/README.md`](../web/README.md).

**Parar:**

```bash
docker compose down       # mantém os dados (o volume do Postgres continua)
docker compose down -v    # apaga também o banco — próxima subida recomeça do zero
```

### Só o backend

Se você não precisa do frontend agora (só API, ou API + Postman):

```bash
cd backend
docker compose up          # este arquivo: só Postgres + API
```

Os dois `compose.yml` não colidem — nomes de projeto diferentes (`votecomdados`
na raiz, `backend` aqui) — mas evite rodar os dois ao mesmo tempo: ambos usam
as portas 5432 e 8080 do host.

Para rodar só o banco (e a API pela IDE, se você tiver JDK local):

```bash
docker compose up -d db
```

## Build e testes

```bash
./mvnd clean test      # Maven em container, com cache
./mvnd -q compile
```

Os testes de integração sobem um Postgres via Testcontainers, aplicam as
migrations e exercitam a API ponta a ponta. Eles carregam mais peso do que o
habitual — ver a nota sobre acesso a dados abaixo.

## Ligar um frontend nativo nesta API

O compose da raiz (acima) já sobe os três juntos. Isto aqui é para quem quer
o frontend rodando **fora** do Docker — hot reload mais rápido — contra uma
API que está em container:

```bash
docker compose up -d db api    # só backend, deste diretório
cd ../web
echo 'NEXT_PUBLIC_API_URL=http://localhost:8080/api/v1' > .env.local
npm install && npm run dev
node scripts/verificar-integracao.mjs   # confirma que consome a API, não fixtures
```

Fora do Docker não existe a distinção servidor/navegador que o compose da
raiz precisa resolver (`API_URL_INTERNO` em `cliente.ts`) — o processo do
`next dev` roda no mesmo host que o navegador, então uma URL só já basta.

## Testar os endpoints

Há uma coleção Postman pronta em [`postman/`](postman/), com 23 requisições e
asserções sobre o contrato. Funciona na extensão Postman do VS Code ou pela
linha de comando:

```bash
npx newman run postman/VoteComDados.postman_collection.json
```

## Decisões que divergem do documentado

**Acesso a dados: `JdbcClient` em vez de jOOQ.** [BACKEND.md](../docs/BACKEND.md)
escolheu jOOQ pela tipagem do SQL. O codegen do jOOQ exige um banco disponível
durante o build; como aqui não há JDK local e tudo compila em container, isso
adicionaria orquestração de rede entre containers a cada build. O `JdbcClient`
preserva a razão principal da escolha original — SQL real, escrito à mão, sem
abstração de ORM — ao custo de perder a verificação em tempo de compilação.
Essa perda é compensada pelos testes de integração, que rodam cada consulta
contra o schema real; um nome de coluna errado falha lá. Revisitar quando
houver JDK local ou CI com Docker-in-Docker.

**Spring Boot 4, não 3.** O 3.5.x está perto do fim de suporte. Duas
diferenças custaram tempo e valem registro:

- As auto-configurações foram divididas em módulos. Ter `flyway-core` no
  classpath **não** executa as migrations — é preciso
  `spring-boot-starter-flyway`. O sintoma é silencioso: a aplicação sobe e as
  consultas falham com "relation does not exist".
- `TestRestTemplate` foi removido. Os testes usam `RestClient` apontando para
  a porta aleatória do servidor.

**CORS liberado por padrão.** Ver `config/CorsConfig.java`: a API é pública,
somente leitura, sem autenticação e sem cookies. Liberar o consumo direto é
coerente com a missão de transparência, e sem credenciais envolvidas `*` é
seguro. Ajustável por `app.cors.origens`.

## Estrutura

```
votecomdados-core/
  src/main/resources/db/migration/
    V1__init.sql                                 schema inicial (espelha db/schema.sql)
    V2__valores_de_enum_do_voto_derivado.sql     LICENCIADO, FORA_DA_COORTE, SITUACAO_NAO_MAPEADA
    V3__exercicio_voto_derivado_e_auditoria.sql  mandato_exercicio, historicos, auditoria da curadoria
  src/main/java/.../core/dominio/                enums e records do domínio
votecomdados-api/
  src/main/java/.../api/
    web/          controllers + envelope de erro
    servico/      casos de uso
    repositorio/  SQL
    config/       CORS
  src/main/resources/
    application.yml            configuração base
    application-dev.yml        perfil dev: carrega o seed
    db/seed-dev/               dados de exemplo (nunca aplicados em produção)
  src/test/                    testes de integração com Testcontainers
```
