# syntax=docker/dockerfile:1
#
# Contexto de build é `backend/`, não este diretório: o reator Maven precisa
# do pom.xml pai e de votecomdados-core, dependência deste módulo.
#
#   docker build -f votecomdados-api/Dockerfile -t votecomdados-api .
#   (rodando de dentro de backend/)
#
# Ver docs/BACKEND.md § 7 (empacotamento) e
# docs/PLANO_DEVSECOPS_IAC.md § Fase 3.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /reator

# pom.xml de todo o reator primeiro — inclui votecomdados-ingestion mesmo
# não sendo empacotado aqui, porque o Maven lê <modules> do pom.xml pai e
# falha se um módulo listado não tiver pom.xml presente no disco.
COPY pom.xml .
COPY votecomdados-core/pom.xml votecomdados-core/pom.xml
COPY votecomdados-api/pom.xml votecomdados-api/pom.xml
COPY votecomdados-ingestion/pom.xml votecomdados-ingestion/pom.xml

COPY votecomdados-core/src votecomdados-core/src
COPY votecomdados-api/src votecomdados-api/src

RUN mvn -B -ntp -pl votecomdados-api -am -DskipTests package

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S votecomdados && adduser -S votecomdados -G votecomdados
USER votecomdados
WORKDIR /app

# Nome com wildcard: a versão do projeto (pom.xml) muda sem que este
# Dockerfile precise acompanhar.
COPY --from=build /reator/votecomdados-api/target/votecomdados-api-*.jar app.jar

EXPOSE 8080

# -XX:MaxRAMPercentage=75: a JVM ignora limite de memória do container se
# não for configurada, e é morta por OOM antes de rodar GC (ver
# docs/BACKEND.md § 3, docs/REVISAO_ARQUITETURA.md R6).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
