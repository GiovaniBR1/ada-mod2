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

# Estagio 2: runtime. Só a JRE 21 distroless (Debian 13), sem shell, sem apt, sem root.
# Fixada por digest: o mesmo Dockerfile sempre usa a mesma base (build reprodutivel).
# A esteira confere a assinatura da base antes do build (cosign verify, ver ci.yml).
FROM gcr.io/distroless/java21-debian13:nonroot@sha256:f3ac871fb5802c2739bb23b67212e67f341cbe754131528d2e9849072f9efb9a
WORKDIR /app
COPY --from=builder --chown=65532:65532 /layers/dependencies/ ./
COPY --from=builder --chown=65532:65532 /layers/spring-boot-loader/ ./
COPY --from=builder --chown=65532:65532 /layers/snapshot-dependencies/ ./
COPY --from=builder --chown=65532:65532 /layers/application/ ./
USER nonroot
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
