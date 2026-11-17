# Single-container demo of the Diligence Platform for Hugging Face Spaces (branch `demo` only, never production).
# Every service of compose.yaml that the application needs runs in this one container under supervisord, bound to
# 127.0.0.1; only the web app listens on the Space port (7860). Data lives in the container and is recreated with
# the demo project on every start. Build context: the Space repository assembled by deploy/huggingface/build-space.sh.

# ------------------------------------------------------------------------------------------------ application builds
FROM eclipse-temurin:21-jdk-noble AS backend
WORKDIR /workspace/apps/backend
COPY apps/backend/gradlew apps/backend/settings.gradle.kts apps/backend/build.gradle.kts apps/backend/gradle.properties ./
COPY apps/backend/gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY apps/backend/src ./src
COPY database/migrations /workspace/database/migrations
RUN ./gradlew --no-daemon bootJar -x test && cp build/libs/app.jar /workspace/app.jar

FROM node:22-bookworm-slim AS frontend
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1 NEXT_PUBLIC_APP_ENVIRONMENT=demo
COPY apps/frontend/package.json apps/frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY apps/frontend/ ./
RUN npm run build

# ------------------------------------------------------------------------------------- services from official images
FROM eclipse-temurin:21-jre-noble AS jre
FROM quay.io/keycloak/keycloak:26.8 AS keycloak
FROM opensearchproject/opensearch:3 AS opensearch
FROM apache/tika:4.2.0-1 AS tika
FROM chrislusf/seaweedfs:4.53 AS seaweedfs

# ------------------------------------------------------------------------------------------------------------ runtime
# Gotenberg's image already carries LibreOffice and Chromium, the hardest parts to install.
FROM gotenberg/gotenberg:8
USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl gnupg supervisor procps python3 openssl \
      redis-server rabbitmq-server clamav-daemon clamav-freshclam \
 && . /etc/os-release \
 && curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc | gpg --dearmor -o /usr/share/keyrings/pgdg.gpg \
 && echo "deb [signed-by=/usr/share/keyrings/pgdg.gpg] https://apt.postgresql.org/pub/repos/apt ${VERSION_CODENAME}-pgdg main" \
      > /etc/apt/sources.list.d/pgdg.list \
 && apt-get update && apt-get install -y --no-install-recommends postgresql-17 postgresql-client-17 \
 && rm -rf /var/lib/apt/lists/*

COPY --from=jre /opt/java/openjdk /opt/java/openjdk
COPY --from=keycloak /opt/keycloak /opt/keycloak
COPY --from=opensearch /usr/share/opensearch /opt/opensearch
COPY --from=tika /tika-server-standard-*.jar /opt/tika/tika-server.jar
COPY --from=seaweedfs /usr/bin/weed /usr/local/bin/weed
COPY --from=frontend /usr/local/bin/node /usr/local/bin/node
COPY --from=backend /workspace/app.jar /opt/backend/app.jar
COPY --from=frontend /app/.next/standalone /opt/frontend/
COPY --from=frontend /app/.next/static /opt/frontend/.next/static
COPY --from=frontend /app/public /opt/frontend/public
COPY --from=frontend /app/messages /opt/frontend/messages

# Realm, password blacklist and the demo setup scripts (they expect the repository layout under /app).
COPY infrastructure/docker/keycloak/diligence-realm.json /opt/keycloak/data/import/diligence-realm.json
COPY infrastructure/docker/keycloak/password-blacklists /opt/keycloak/data/password-blacklists
COPY infrastructure/docker/scripts/keycloak-bootstrap.sh infrastructure/docker/scripts/demo-setup.sh \
     infrastructure/docker/scripts/demo-seed.py /app/infrastructure/docker/scripts/
COPY deploy/huggingface/supervisord.conf /etc/supervisor/demo.conf
COPY deploy/huggingface/opensearch.yml /opt/opensearch/config/opensearch.yml
COPY deploy/huggingface/clamd.conf /etc/clamav/demo-clamd.conf
COPY deploy/huggingface/start.sh deploy/huggingface/setup.sh /app/

# Virus signatures at build time, so the container starts without a 300 MB download. Hugging Face runs the
# container as uid 1000: everything it writes belongs to that user.
RUN freshclam --stdout --foreground \
 && (id -u 1000 > /dev/null 2>&1 || useradd --create-home --uid 1000 user) \
 && mkdir -p /data \
 && ln -s /data/.env /app/.env \
 && chmod +x /app/start.sh /app/setup.sh /app/infrastructure/docker/scripts/*.sh \
 && rm -rf /opt/opensearch/plugins/opensearch-performance-analyzer \
 && chown -R 1000:1000 /data /app /opt/keycloak /opt/opensearch /var/lib/clamav /var/log/clamav \
      /var/lib/rabbitmq /var/log/rabbitmq /etc/rabbitmq

USER 1000
ENV HOME=/data/home JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:/usr/lib/postgresql/17/bin:$PATH
EXPOSE 7860
ENTRYPOINT ["/app/start.sh"]
