# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1 — build the runnable fat jar with the project's own Maven wrapper.
# Using a JDK 17 image matches <java.version>17</java.version> in pom.xml.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Warm the dependency cache first: copy only what resolves dependencies, so
# this layer is reused unless the build config actually changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

# Now copy sources and build. Skip tests in the image build — they run in CI /
# locally; the image is just for producing the artifact.
COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests

# ---------------------------------------------------------------------------
# Stage 2 — slim runtime image with only a JRE + the jar.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# Run as a non-root user (good practice; some hosts require it).
RUN useradd --system --uid 10001 appuser
USER appuser

# The pom sets <finalName>dev-productivity-graph</finalName>, so the fat jar is
# predictably named regardless of version.
COPY --from=build /app/target/dev-productivity-graph.jar app.jar

# Most free hosts inject $PORT; application.properties maps server.port to it
# (default 8080). EXPOSE is documentation only — the app honours $PORT.
EXPOSE 8080

# NEO4J_PASSWORD (and optionally NEO4J_URI / NEO4J_USER) must be supplied as
# environment variables by the host — they are never baked into the image.
ENTRYPOINT ["java", "-jar", "app.jar"]
