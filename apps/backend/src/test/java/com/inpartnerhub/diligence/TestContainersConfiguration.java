package com.inpartnerhub.diligence;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Real PostgreSQL and RabbitMQ for {@code @Tag("integration")} tests (requires Docker).
 *
 * <p>The database is set up like a deployed one (#177): Flyway migrates as the schema owner, the application logs in
 * as {@code diligence_app}, which owns nothing. Tests seed and check data with {@link OwnerDatabase}, outside row-level
 * security, the way they did when the application itself logged in as the owner.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfiguration {

    static final String RUNTIME_USER = "diligence_app";
    static final String RUNTIME_PASSWORD = "runtime-test-only";

    @Bean
    PostgreSQLContainer postgres() {
        // V5 creates diligence_app only if it does not exist; a deployment gives it LOGIN and a password (#177).
        return new PostgreSQLContainer("postgres:17-alpine").withCopyToContainer(
                Transferable.of("CREATE ROLE " + RUNTIME_USER + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "';"),
                "/docker-entrypoint-initdb.d/01-runtime-login.sql");
    }

    @Bean
    DynamicPropertyRegistrar databaseLogins(PostgreSQLContainer postgres) {
        return registry -> {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", () -> RUNTIME_USER);
            registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
            registry.add("spring.flyway.user", postgres::getUsername);
            registry.add("spring.flyway.password", postgres::getPassword);
        };
    }

    @Bean
    OwnerDatabase ownerDatabase(PostgreSQLContainer postgres) {
        return new OwnerDatabase(new JdbcTemplate(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())));
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbit() {
        return new RabbitMQContainer("rabbitmq:4-management-alpine");
    }

    /**
     * The schema owner's connection, for seeding and checking test data outside row-level security. Not a
     * {@code DataSource} or {@code JdbcTemplate} bean, so the application's own beans stay the auto-configured ones.
     */
    public record OwnerDatabase(JdbcTemplate jdbc) {
    }
}
