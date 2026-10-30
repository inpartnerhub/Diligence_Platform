# syntax=docker/dockerfile:1.7
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
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/application/ ./
USER 10001:10001
EXPOSE 8080 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
