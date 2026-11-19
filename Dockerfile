# syntax=docker/dockerfile:1

# ---- Build stage: compile and package with the Maven wrapper (JDK only here) ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Dependencies first, so they stay cached as long as pom.xml does not change.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 sh mvnw -B -q dependency:go-offline

COPY src src
# Tests run in CI / locally (integration tests need Docker via Testcontainers), not inside the image build.
RUN --mount=type=cache,target=/root/.m2 sh mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination target/extracted

# ---- Runtime stage: JRE only, non-root, layered for Docker cache efficiency ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --no-create-home app

# Least-changing layers first: a code change only rebuilds the last (small) layer.
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./

USER app
EXPOSE 8080

# Size the heap from the container memory limit (K8s resources.limits.memory), not from the host.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
