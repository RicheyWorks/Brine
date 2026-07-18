# Brine

The sixth engine of the ecosystem: the **adaptive cache** — where things soak before they're
needed. A read-through cache over [SmokeHouse](../SmokeHouse) whose eviction policy is not
chosen but **evolved**: [CSRBT](../CSRBT)'s experimental cache-evolution loop (founders →
shadow trials → viability gate → (μ+λ) selection → gated promotion) runs against the live
access pattern, and Brine serves values out of whatever champion currently holds the throne.

This is `csrbt-experimental`'s first production consumer — the publication trigger ADR-013 §4
has been holding for.

```java
try (SmokeHouse<Long, Order> store = SmokeHouse.open(dir, opts);
     Brine<Long, Order> brine = Brine.over(store, 4_096, 8_192, seed)) {

    Order o = brine.get(id);      // read-through: memory if the champion holds it, store if not
    brine.champion();             // the currently serving CacheGenome
    brine.lastVerdict();          // the latest generation's one-line verdict
    brine.stats();                // gets / valueHits / storeReads / invalidations / champion
}
```

## Design notes

- **Policy and values are separate organs.** The evolution loop decides which keys deserve
  residency (over dense int ids); Brine keeps the values and trims its map to the champion's
  actual residency via the `resident(id)` seam this consumer named upstream.
- **Coherence rides the log.** One tail subscription invalidates on every committed mutation;
  a tail gap drops the whole cache — a cold start, never wrong data. The write-to-invalidation
  staleness window is bounded and documented, not hidden.
- **Caller-cadenced evolution.** Generations turn over every `windowOps` lookups on the
  caller's thread — no clock, no threads of Brine's own. Seeded: same seed + same traffic ⇒
  same champion lineage (tested).

## Build

```bash
# Requires ../SmokeHouse, ../SuperBeefSort, ../CSRBT cloned as siblings (nested composite build)
./gradlew build
```

Java 17+, Gradle 9.5.1 (bundled wrapper). Tests are seeded oracle tests (`TreeMap` store
reference) in the house style. MIT license.
