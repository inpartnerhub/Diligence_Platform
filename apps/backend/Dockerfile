# syntax=docker/dockerfile:1.10
# Build context: repository root (needs database/migrations). See compose.yaml / CI.

FROM eclipse-temurin:24-jdk-noble AS build
WORKDIR /workspace/apps/backend
COPY apps/backend/gradlew apps/backend/settings.gradle.kts apps/backend/build.gradle.kts apps/backend/gradle.properties ./
COPY apps/backend/gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies > /dev/null
COPY apps/backend/src ./src
COPY database/migrations /workspace/database/migrations
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test \
 && java -Djarmode=tools -jar build/libs/app.jar extract --layers --launcher --destination /workspace/extracted

FROM eclipse-temurin:24-jre-noble AS runtime
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/application/ ./
USER 10001:10001
EXPOSE 8080 8081
# For Docker/Compose; Kubernetes uses its own probes. bash /dev/tcp avoids adding curl to the runtime image.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081 && printf "GET /actuator/health/liveness HTTP/1.0\r\n\r\n" >&3 && grep -q "\"UP\"" <&3'

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
