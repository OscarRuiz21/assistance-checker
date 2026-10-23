# syntax=docker/dockerfile:1.7

# ─────────────────────────────────────────────────────────────────────────
# Stage 1: build del frontend (React + Vite) → emite a
# backend/src/main/resources/static para que Spring Boot lo sirva same-origin.
# ─────────────────────────────────────────────────────────────────────────
FROM node:20-alpine AS frontend-build
WORKDIR /workspace/frontend

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./
# vite build emite a ../backend/src/main/resources/static (ver vite.config.ts)
RUN mkdir -p /workspace/backend/src/main/resources/static \
    && npm run build

# ─────────────────────────────────────────────────────────────────────────
# Stage 2: dependencias Gradle (capa cacheable por separado del código)
# ─────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS backend-deps
WORKDIR /workspace/backend

COPY backend/gradlew ./
COPY backend/gradle ./gradle
COPY backend/build.gradle.kts backend/settings.gradle.kts ./
# Descarga de dependencias en capa propia: cambia poco entre builds.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon dependencies > /dev/null || true

# ─────────────────────────────────────────────────────────────────────────
# Stage 3: build del backend (bootJar, layered jar habilitado por defecto
# en el plugin de Spring Boot 3.x) usando el frontend ya compilado.
# ─────────────────────────────────────────────────────────────────────────
FROM backend-deps AS backend-build
WORKDIR /workspace/backend

COPY backend/ ./
COPY --from=frontend-build /workspace/backend/src/main/resources/static ./src/main/resources/static

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -x test

# Extrae las capas del jar (dependencies, spring-boot-loader, snapshot-dependencies,
# application) para que Docker las cachee de forma independiente.
RUN JAR_FILE=$(ls build/libs/*.jar | grep -v plain) \
    && mkdir -p /workspace/extracted \
    && java -Djarmode=layertools -jar "$JAR_FILE" extract --destination /workspace/extracted

# ─────────────────────────────────────────────────────────────────────────
# Stage 4: imagen final, JRE mínimo + usuario no-root.
# Nota: eclipse-temurin:21-jre-alpine no publica artefacto -alpine, se usa
# -jre que es Ubuntu/Debian-based minimal. Si se prefiere distroless
# (gcr.io/distroless/java21-debian12:nonroot), el HEALTHCHECK de Docker
# NO aplica (no hay shell/curl): el chequeo de salud debe hacerlo el
# orquestador (ALB target group / ECS healthcheck) contra /actuator/health.
# ─────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS final

RUN addgroup -S assistance && adduser -S assistance -G assistance
RUN apk add --no-cache curl

WORKDIR /app

COPY --from=backend-build /workspace/extracted/dependencies/ ./
COPY --from=backend-build /workspace/extracted/spring-boot-loader/ ./
COPY --from=backend-build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=backend-build /workspace/extracted/application/ ./

USER assistance

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"
ENV SERVER_PORT=8080
ENV MANAGEMENT_PORT=8081

EXPOSE 8080 8081

# HEALTHCHECK usa el management port (solo health está expuesto ahí según
# el contrato de la app). Si se migra a distroless, eliminar este bloque y
# documentar que el orquestador (ALB/ECS) hace el healthcheck en su lugar.
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl -f "http://localhost:${MANAGEMENT_PORT}/actuator/health" || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
