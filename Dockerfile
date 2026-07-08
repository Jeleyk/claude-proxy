# ---- Stage 1: build the React UI ----
FROM node:22-slim AS frontend
WORKDIR /app/frontend
RUN corepack enable && corepack prepare pnpm@9.15.9 --activate
COPY frontend/package.json ./
RUN pnpm install --no-frozen-lockfile
COPY frontend/ ./
# Emit straight to an absolute path picked up by the backend stage.
RUN pnpm exec vite build --outDir /static --emptyOutDir

# ---- Stage 2: build the fat jar (UI baked in) ----
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /app
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
RUN ./gradlew --version --no-daemon >/dev/null 2>&1 || true
COPY src ./src
COPY --from=frontend /static ./src/main/resources/static
RUN ./gradlew fatJar --no-daemon

# ---- Stage 3: slim runtime ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend /app/build/libs/claude-proxy-*-all.jar app.jar
ENV BIND_HOST=0.0.0.0 \
    PORT=8787 \
    DB_PATH=/data/claude-proxy.db
EXPOSE 8787
VOLUME /data
ENTRYPOINT ["java", "-jar", "app.jar"]
