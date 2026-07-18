plugins {
    `java-library`
}

group = "io.github.richeyworks"
version = "0.1.0"

java {
    withSourcesJar()
}

// Mirror the siblings: 17-target bytecode from whatever JDK runs Gradle (Gradle 9 needs 17+).
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    // Resolved to the live sibling sources via the composite build in settings.gradle.kts.
    api("io.github.richeyworks:smokehouse:0.1.0")
    // The evolution machinery — this dependency IS the point: Brine is csrbt-experimental's
    // first external consumer, the publication trigger ADR-013 §4 has been holding for.
    // CacheGenome appears in Brine's public surface (champion()), so it is `api`.
    api("io.github.richeyworks:csrbt-experimental:0.1.0")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // csrbt logs via log4j-api with no backend on the classpath; keep tests quiet.
    systemProperty("log4j2.loggerContextFactory",
            "org.apache.logging.log4j.simple.SimpleLoggerContextFactory")
    systemProperty("org.apache.logging.log4j.simplelog.StatusLogger.level", "OFF")
}
