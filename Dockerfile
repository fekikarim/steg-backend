# Phase A14 — multi-stage build: Maven compiles + packages, the runtime image
# ships only a slim JRE 21 and the boot jar (no JDK, no Maven, no sources).
# Tests are intentionally skipped here (they run in CI, where Testcontainers
# has a Docker daemon; `docker build` alone must stay hermetic and fast).

FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /app

# Dependency layer first for better layer caching.
COPY pom.xml mvnw mvnw.cmd ./
COPY .mvn .mvn
RUN ./mvnw -q -B dependency:go-offline

COPY src src
RUN ./mvnw -q -B -DskipTests package

# ---------------------------------------------------------------- runtime ---
FROM eclipse-temurin:21-jre-jammy AS runtime

# curl only for the container healthcheck below.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r steg && useradd -r -g steg steg

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /app/storage/uploads && chown -R steg:steg /app

USER steg
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

# /actuator/health is public by design (UP/DOWN only, no component detail),
# so the orchestrator can probe readiness without credentials.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
