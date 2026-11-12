FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
# Motor Cobalt (fork jonales/Cobalt): fora do Maven Central, instalado no commit fixado no script
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
COPY scripts/install-cobalt.sh scripts/
RUN scripts/install-cobalt.sh
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
# ffmpeg: duração de áudio/vídeo enviados e conversão de voz para OGG/Opus
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && ffmpeg -hide_banner -version | head -n 1
RUN useradd -r -u 1001 msgforge \
    && mkdir -p /data/cobalt && chown msgforge /data/cobalt
# Sessões do Cobalt (uma pasta por instância): monte um volume aqui para sobreviverem ao container
VOLUME /data/cobalt
ENV COBALT_SESSION_DIR=/data/cobalt
COPY --from=build /app/target/msgforge-api-*.jar app.jar
# Avisos de licença dos componentes de terceiros distribuídos na imagem
COPY THIRD-PARTY-NOTICES.md /app/
USER msgforge
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
