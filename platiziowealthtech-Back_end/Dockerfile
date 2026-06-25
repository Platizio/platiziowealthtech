# syntax=docker/dockerfile:1
# Multi-stage build for the Platizio Wealthtech backend (Spring Boot, Java 21).
# Used by render.yaml (repo root) as a Docker web service. dockerContext is this
# directory (platiziowealthtech-Back_end), so all paths below are relative to it.

# ---- build stage ----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Cache dependencies first (pom rarely changes vs. src).
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Tests run in CI, not in the image build.
RUN mvn -B -q clean package -DskipTests

# ---- run stage ------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Render assigns a port via $PORT; the app reads SERVER_PORT (default 8081).
# Bind to $PORT when present so the managed health check can reach the service.
ENV JAVA_OPTS=""
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT:-${SERVER_PORT:-8081}} -jar /app/app.jar"]
