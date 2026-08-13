FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS layers
WORKDIR /layers
COPY --from=build /build/target/orderflow-reconciliation-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S conciliacao && adduser -S conciliacao -G conciliacao
RUN apk add --no-cache curl

COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./

# Diretorio dos arquivos enviados. Como volume, sobrevive ao restart do container --
# um job que caiu no meio pode ser reprocessado sem novo upload.
RUN mkdir -p /app/arquivos && chown conciliacao:conciliacao /app/arquivos
VOLUME /app/arquivos
ENV STORAGE_DIR=/app/arquivos

USER conciliacao
EXPOSE 8082

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
    CMD curl -fsS http://localhost:8082/actuator/health/liveness || exit 1

#
# O `jarmode=tools` da etapa anterior produz `app.jar` + `lib/`, com o Class-Path
# no manifesto: quem inicia e o `java -jar`. O JarLauncher pertence ao formato
# antigo (`jarmode=layertools`), que explodia as classes do loader na imagem.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", \
            "-jar", "app.jar"]
