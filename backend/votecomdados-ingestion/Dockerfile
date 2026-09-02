# syntax=docker/dockerfile:1
#
# Contexto de build é `backend/`, não este diretório — mesmo motivo do
# Dockerfile de votecomdados-api.
#
#   docker build -f votecomdados-ingestion/Dockerfile -t votecomdados-ingestion .
#   (rodando de dentro de backend/)
#
# Ver docs/BACKEND.md § 7 (empacotamento) e
# docs/PLANO_DEVSECOPS_IAC.md § Fase 3.
#
# Sem EXPOSE: este módulo não tem spring-boot-starter-web de propósito — é
# um job batch (ApplicationRunner + System.exit), não um service. Disparado
# pelo EventBridge Scheduler via `ecs:RunTask`, sem ALB associado.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /reator

COPY pom.xml .
COPY votecomdados-core/pom.xml votecomdados-core/pom.xml
COPY votecomdados-api/pom.xml votecomdados-api/pom.xml
COPY votecomdados-ingestion/pom.xml votecomdados-ingestion/pom.xml

COPY votecomdados-core/src votecomdados-core/src
COPY votecomdados-ingestion/src votecomdados-ingestion/src

RUN mvn -B -ntp -pl votecomdados-ingestion -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S votecomdados && adduser -S votecomdados -G votecomdados
USER votecomdados
WORKDIR /app

COPY --from=build /reator/votecomdados-ingestion/target/votecomdados-ingestion-*.jar app.jar

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
