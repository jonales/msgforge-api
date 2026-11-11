---
type: "query"
date: "2026-11-11T13:48:02.560220+00:00"
question: "Como rodar build e testes no container de nuvem?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["MsgforgeApplication"]
---

# Q: Como rodar build e testes no container de nuvem?

## Answer

A imagem padrão tem JDK 21 e o projeto exige Java 25 (unnamed patterns, release 25). O SessionStart hook instala openjdk-25-jdk-headless via apt e exporta JAVA_HOME. Para subir a app sem Docker: Postgres 16 local (initdb + pg_ctl com usuário/db msgforge) e redis-server; use 'mvn clean package', porque sem clean o target/classes guarda migrations apagadas e o Flyway reclama.

## Outcome

- Signal: useful

## Source Nodes

- MsgforgeApplication