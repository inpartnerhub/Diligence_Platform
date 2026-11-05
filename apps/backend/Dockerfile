# syntax=docker/dockerfile:1.10
# Build context: repository root (needs database/migrations). See compose.yaml / CI.

FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace/apps/backend
COPY apps/backend/gradlew apps/backend/settings.gradle.kts apps/backend/build.gradle.kts apps/backend/gradle.properties ./
COPY apps/backend/gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies > /dev/null
COPY apps/backend/src ./src
COPY database/migrations /workspace/database/migrations
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test \
 && java -Djarmode=tools -jar build/libs/app.jar extract --layers --launcher --destination /workspace/extracted

FROM eclipse-temurin:21-jre-noble AS runtime
# Patch the OS, then drop apt itself: the JVM never installs packages at runtime, and apt's own CVEs
# (e.g. CVE-2026-109518 in libapt-pkg) would otherwise fail the image scan.
RUN apt-get update \
 && apt-get upgrade -y \
 && rm -rf /var/lib/apt/lists/* /var/cache/apt \
 && dpkg --purge --force-remove-protected --force-depends apt libapt-pkg6.0t64 \
 && groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
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
