# Production Keycloak image (#92, ADR-0009, ADR-0011): pre-built for PostgreSQL, health/metrics enabled,
# breached-password blacklist bundled, and running as UID/GID 10102 (Trivy KSV-0020/0021) for GHCR.
FROM quay.io/keycloak/keycloak:26.8.2@sha256:7e66a96e4739239070aeaf72e4c5decf0f7f682cfc725b9f7ea2b336eaba38b3 AS builder

ENV KC_DB=postgres
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true

COPY password-blacklists /opt/keycloak/data/password-blacklists
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.2@sha256:7e66a96e4739239070aeaf72e4c5decf0f7f682cfc725b9f7ea2b336eaba38b3

USER root
COPY --from=builder /opt/keycloak/ /opt/keycloak/
COPY password-blacklists /opt/keycloak/data/password-blacklists
RUN chown -R 10102:10102 /opt/keycloak

USER 10102:10102
EXPOSE 8080 9000

# For docker run/compose; Kubernetes uses its own probes.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/9000 && printf 'GET /health/ready HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q '200 OK' <&3"]

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
