plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.sonarqube)
}

java {
    // Built with whatever JDK runs Gradle; bytecode targets the Java 21 LTS runtime image.
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.modulith.bom.get().toString())
        mavenBom(libs.aws.sdk.bom.get().toString())
        mavenBom(libs.sentry.bom.get().toString())
    }
}

dependencies {
    // Web & API
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-restclient") // Keycloak Admin API client
    implementation(libs.springdoc.webmvc.ui)

    // Security (Keycloak / OIDC resource server)
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")

    // Data
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("software.amazon.awssdk:s3")
    implementation(libs.opensearch.java)
    implementation("org.apache.httpcomponents.client5:httpclient5")

    // Messaging & notifications
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // Modular monolith
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
    implementation("org.springframework.modulith:spring-modulith-events-amqp")
    runtimeOnly("org.springframework.modulith:spring-modulith-actuator")
    runtimeOnly("org.springframework.modulith:spring-modulith-observability")

    // Observability
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("io.sentry:sentry-spring-boot-4-starter")

    // Tests
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-rabbitmq")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Flyway migrations live in /database at the monorepo root and are packaged onto the classpath.
val migrationsDir = layout.projectDirectory.dir("../../database/migrations/postgresql")
tasks.processResources {
    from(migrationsDir) { into("db/migration") }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
}

tasks.test {
    useJUnitPlatform { excludeTags("integration") }
    finalizedBy(tasks.jacocoTestReport, tasks.jacocoTestCoverageVerification)
}

tasks.register<Test>("integrationTest") {
    description = "Runs Testcontainers-backed integration tests (requires Docker)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
}

tasks.jacocoTestReport {
    reports { xml.required = true }
}

// PRD Bab 7: ≥ 70% unit-test coverage for business logic (services and domain rules, not adapters/controllers).
val businessLogic = listOf(
    "com/inpartnerhub/diligence/**/*Service.class",
    "com/inpartnerhub/diligence/finding/Risk*.class",
    "com/inpartnerhub/diligence/security/Role.class",
)
tasks.jacocoTestCoverageVerification {
    classDirectories.setFrom(sourceSets.main.get().output.classesDirs.asFileTree.matching { include(businessLogic) })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}
tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }

tasks.bootJar {
    archiveFileName = "app.jar"
}

tasks.jar {
    enabled = false
}

sonar {
    properties {
        property("sonar.projectKey", "diligence-backend")
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco/test/jacocoTestReport.xml")
    }
}
