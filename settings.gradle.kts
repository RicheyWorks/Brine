rootProject.name = "brine"

// Composite build: Brine is the sixth engine of the ecosystem — the adaptive cache, where
// things soak before they're needed. Including SmokeHouse's build transitively includes
// SuperBeefSort and CSRBT (nested composites); Gradle substitutes every published coordinate
// — including csrbt-experimental, whose first external consumer this is (ADR-013 §4's held
// publication trigger) — with the live sibling sources.
includeBuild("../SmokeHouse")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
