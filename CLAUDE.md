# Brine — working notes for agents

## What it is
The ecosystem's sixth engine: a read-through cache over SmokeHouse whose eviction policy is
evolved per-workload by `csrbt-experimental`'s `CacheEvolutionLoop`. One class (`Brine`):
policy layer = the loop over dense int ids; value layer = `key → V` trimmed to the champion's
residency; coherence = one tail subscription invalidating on every committed mutation.

## Build & test
- Nested composite: requires `../SmokeHouse`, `../SuperBeefSort`, `../CSRBT` as siblings.
  `./gradlew build` runs everything (Gradle 9 wrapper; JVM 17+).
- Tests are seeded oracle tests (`TreeMap` reference in `BrineTest`). Invalidation rides the
  tail thread — poll (`eventually`) after writes; never assert freshness without it. Evolution
  tests use short-cooldown `MorphPolicy` gates so promotions happen at test scale.

## Git is host-side
Same as the siblings: agent sandboxes cannot write `.git`. Run all git commands from a host
terminal (PowerShell). Stale `.git/index.lock` fix: `Remove-Item .git\index.lock -Force`.

## Invariants (do not break)
- **Never serve wrong data.** Stale-until-invalidated is the documented contract; anything
  stronger must come from the log, not from guessing. A tail gap drops the entire cache —
  a cold start is always acceptable, a wrong value never is.
- **The loop owns policy; Brine owns values.** Don't graft eviction decisions into Brine —
  if the policy layer needs a new signal, name a seam upstream (`resident()`/`champion()`
  were named this way) with the evidence that requires it.
- **Caller-cadenced, single-threaded core.** Generations turn on the caller's thread every
  `windowOps` lookups; the tail thread only invalidates. All state guarded by `this`.
- **Determinism is a feature.** Same seed + same access sequence ⇒ same champion lineage
  (`sameSeedSameTrafficSameChampionLineage` guards it).

## Roadmap seams (measure before cutting — the ring's rule)
- JMH: hit-rate + latency vs a fixed-policy baseline (does evolution actually beat plain
  segmented LRU on real shapes? — the transfer experiment's production replication).
- Negative caching (remember misses) — only with a benchmark showing miss-storms matter.
- `csrbt-experimental` publication: Brine is the consumer ADR-013 §4 was waiting for; firing
  the trigger is a release decision to make deliberately, not a side effect.
