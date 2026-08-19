plugins {
    `java-library`
    `maven-publish`   // Phase 9: local repo today; Central rides csrbt-core's release
    signing
    alias(libs.plugins.jmh)   // measure phase: ./gradlew jmh (benchmarks in src/jmh/java)
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

// The measure rig: does evolution actually beat a fixed policy? (The transfer experiment's
// production replication.) Run: ./gradlew jmh (results at build/reports/jmh/results.json)
val jmhVer = libs.versions.jmh.asProvider().get()

jmh {
    jmhVersion = jmhVer
    fork = 1
    warmupIterations = 3
    iterations = 5
    resultFormat = "JSON"
    resultsFile = layout.buildDirectory.file("reports/jmh/results.json")
    jvmArgs.add("-Dlog4j2.loggerContextFactory="
            + "org.apache.logging.log4j.simple.SimpleLoggerContextFactory")
    jvmArgs.add("-Dorg.apache.logging.log4j.simplelog.StatusLogger.level=OFF")
}

// The jmh plugin doesn't hook the jmh source set into `build`/`check`, so a compile
// break in a benchmark would only surface at the next manual jmh run. Feed it in.
// (Mirrors every sibling.)
tasks.named("check") { dependsOn(tasks.named("compileJmhJava")) }

// Phase 9 (outer-ring ADR): make the ring locally installable — ./gradlew publishToMavenLocal.
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "brine"
            from(components["java"])
            pom {
                name = "Brine"
                description = "An adaptive read-through cache whose eviction policy is evolved per workload — the sixth engine of the CSRBT ecosystem."
                url = "https://github.com/RicheyWorks/Brine"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
                developers {
                    developer {
                        id = "RicheyWorks"
                        name = "Richmond"
                    }
                }
                scm {
                    url = "https://github.com/RicheyWorks/Brine"
                    connection = "scm:git:https://github.com/RicheyWorks/Brine.git"
                }
            }
        }
    }
}

// Phase 9 release prep: Central requires a javadoc jar per artifact.
java {
    withJavadocJar()
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("Xdoclint:none", "-quiet")
    }
}

// Phase 9 release prep: PGP signing + a local staging layout for the Central Portal bundle.
// Signing activates ONLY when SIGNING_KEY is present in the environment, so everyday local
// builds stay signature-free. Stage with: ./gradlew publishMavenPublicationToStagingRepository
publishing {
    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

signing {
    val key = providers.environmentVariable("SIGNING_KEY").orNull
    val pass = providers.environmentVariable("SIGNING_PASSWORD").orNull
    isRequired = key != null
    if (key != null) {
        useInMemoryPgpKeys(key, pass)
        sign(publishing.publications["maven"])
    }
}
