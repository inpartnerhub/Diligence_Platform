# Production Keycloak image (#92, ADR-0009, ADR-0011): pre-built for PostgreSQL, health/metrics enabled,
# breached-password blacklist bundled, and running as UID/GID 10102 (Trivy KSV-0020/0021) for GHCR.
FROM quay.io/keycloak/keycloak:26.8 AS builder

ENV KC_DB=postgres
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true

COPY password-blacklists /opt/keycloak/data/password-blacklists
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8

USER root
COPY --from=builder /opt/keycloak/ /opt/keycloak/
COPY password-blacklists /opt/keycloak/data/password-blacklists
RUN chown -R 10102:10102 /opt/keycloak

USER 10102:10102
EXPOSE 8080 9000

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
