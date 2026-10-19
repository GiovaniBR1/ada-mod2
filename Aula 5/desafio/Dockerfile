# syntax=docker/dockerfile:1.7
# Estagio 1: build (JDK 21 + Maven). Nada daqui vai para a imagem final.
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 apt-get update >/dev/null && apt-get install -y --no-install-recommends maven >/dev/null \
    && mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/checkout-release-*.jar extract --layers --launcher --destination /layers

# Estagio 2: runtime. Só a JRE 21 distroless, sem shell, sem apt, sem root.
FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=builder --chown=65532:65532 /layers/dependencies/ ./
COPY --from=builder --chown=65532:65532 /layers/spring-boot-loader/ ./
COPY --from=builder --chown=65532:65532 /layers/snapshot-dependencies/ ./
COPY --from=builder --chown=65532:65532 /layers/application/ ./
USER nonroot
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
