plugins {
    // Auto-provisions the Java 21 toolchain when it is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "platform-backend"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
