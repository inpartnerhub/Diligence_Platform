package com.inpartnerhub.diligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.health.DataRedisHealthIndicator;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.json.JsonMapper;

/**
 * #171: each workload has its own ElastiCache RBAC user, restricted by key pattern. The access strings in
 * {@code infrastructure/terraform/modules/redis/access-strings.json} (the file Terraform reads) are applied to a real
 * Valkey, the engine ElastiCache runs. The backend's Redis operations run through Spring Boot's own Redis and cache
 * auto-configuration, bound from {@code application.yml}, as the {@code backend} user; no workload reaches another's
 * keys, and nobody but the web app reads sessions.
 */
@Tag("integration")
@Testcontainers
class RedisAccessStringsIntegrationTest {

    private static final Path ACCESS_STRINGS = Path.of("../../infrastructure/terraform/modules/redis/access-strings.json");
    private static final String ADMIN_PASSWORD = "admin-test-only";

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.0")
            .withCommand("valkey-server", "--requirepass", ADMIN_PASSWORD)
            .withExposedPorts(6379);

    @BeforeAll
    static void createUsers() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, String> accessStrings = JsonMapper.builder().build().readValue(Files.readString(ACCESS_STRINGS),
                Map.class);
        for (var user : List.of("backend", "web", "gateway")) {
            var command = new ArrayList<>(List.of("valkey-cli", "--no-auth-warning", "-a", ADMIN_PASSWORD,
                    "ACL", "SETUSER", user, "reset", ">" + password(user)));
            command.addAll(List.of(accessStrings.get(user).split(" ")));
            var result = VALKEY.execInContainer(command.toArray(String[]::new));
            assertThat(result.getStdout().trim()).as("ACL SETUSER %s: %s", user, result.getStderr()).isEqualTo("OK");
        }
    }

    @Test
    void theBackendUserRunsTheBackendsRedisOperations() {
        backend("backend", password("backend")).run(context -> {
            var redis = context.getBean(StringRedisTemplate.class);
            // UserRateLimitFilter and MassDownloadGuard: INCR/INCRBY + EXPIRE on their keys.
            assertThat(redis.opsForValue().increment("diligence:ratelimit:STANDARD:user:1")).isEqualTo(1);
            assertThat(redis.expire("diligence:ratelimit:STANDARD:user:1", Duration.ofMinutes(2))).isTrue();
            assertThat(redis.opsForValue().increment("diligence:downloads:user:1", 3)).isEqualTo(3);
            assertThat(redis.expire("diligence:downloads:user:1", Duration.ofMinutes(2))).isTrue();

            // Reference-data caches with the key prefix from application.yml; clear() uses KEYS.
            var cache = context.getBean(CacheManager.class).getCache("organizations");
            cache.put("id", "PT Klien");
            assertThat(await(cache, "id").get()).isEqualTo("PT Klien");
            assertThat(redis.keys("diligence:cache:organizations::*")).isNotEmpty();
            assertThat(cache.evictIfPresent("id")).isTrue();
            cache.put("other", "PT Target");
            await(cache, "other");
            cache.invalidate();
            assertThat(cache.get("other")).isNull();

            // Actuator health (INFO).
            var health = new DataRedisHealthIndicator(context.getBean(RedisConnectionFactory.class)).health();
            assertThat(health.getStatus().getCode()).isEqualTo("UP");

            // Never the web app's sessions, nor Kong's counters.
            assertThatThrownBy(() -> redis.opsForValue().get("diligence:session:abc")).hasStackTraceContaining("NOPERM");
            assertThatThrownBy(() -> redis.opsForValue().get("diligence:web:ratelimit:ip")).hasStackTraceContaining("NOPERM");
            assertThatThrownBy(() -> redis.opsForValue().get("ratelimit:route:service:1.2.3.4:0:minute"))
                    .hasStackTraceContaining("NOPERM");
        });
    }

    @Test
    void onlyTheWebUserReadsSessions() {
        backend("web", password("web")).run(context -> {
            var redis = context.getBean(StringRedisTemplate.class);
            redis.opsForValue().set("diligence:session:abc", "encrypted", Duration.ofMinutes(1));
            assertThat(redis.opsForValue().get("diligence:session:abc")).isEqualTo("encrypted");
            assertThatThrownBy(() -> redis.opsForValue().get("diligence:cache:organizations::id"))
                    .hasStackTraceContaining("NOPERM");
        });
        backend("gateway", password("gateway")).run(context -> {
            var redis = context.getBean(StringRedisTemplate.class);
            assertThat(redis.opsForValue().increment("ratelimit:route:service:1.2.3.4:0:minute")).isEqualTo(1);
            assertThatThrownBy(() -> redis.opsForValue().get("diligence:session:abc")).hasStackTraceContaining("NOPERM");
            assertThatThrownBy(() -> redis.opsForValue().get("diligence:ratelimit:STANDARD:user:1"))
                    .hasStackTraceContaining("NOPERM");
        });
    }

    @Test
    void anEmptyUsernameUsesTheDefaultUserAsLocally() {
        backend("", ADMIN_PASSWORD).run(context ->
                assertThat(context.getBean(StringRedisTemplate.class).getConnectionFactory().getConnection().ping())
                        .isEqualTo("PONG"));
    }

    /** Spring Boot's Redis and cache auto-configuration bound from the real application.yml, as the given user. */
    private static ApplicationContextRunner backend(String username, String password) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class, CacheAutoConfiguration.class))
                .withUserConfiguration(Caching.class)
                .withInitializer(context -> {
                    try {
                        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                                .forEach(context.getEnvironment().getPropertySources()::addLast);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .withPropertyValues("REDIS_HOST=" + VALKEY.getHost(), "REDIS_PORT=" + VALKEY.getMappedPort(6379),
                        "REDIS_USERNAME=" + username, "REDIS_PASSWORD=" + password, "REDIS_SSL_ENABLED=false");
    }

    private static String password(String user) {
        return "pw-" + user + "-test-only";
    }

    private static Cache.ValueWrapper await(Cache cache, Object key) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        var value = cache.get(key);
        while (value == null && System.nanoTime() < deadline) {
            Thread.sleep(20);
            value = cache.get(key);
        }
        assertThat(value).as("cache entry %s", key).isNotNull();
        return value;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class Caching {
    }
}
