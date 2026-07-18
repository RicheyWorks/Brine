package io.github.richeyworks.brine;

import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.experimental.cache.CacheEvolutionLoop;
import io.github.richeyworks.csrbt.experimental.cache.CacheGenome;
import io.github.richeyworks.smokehouse.SmokeHouse;
import io.github.richeyworks.smokehouse.TailEvent;
import io.github.richeyworks.smokehouse.TailListener;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Brine — the sixth engine of the ecosystem: the adaptive cache, where things soak before
 * they're needed. A read-through cache over a {@link SmokeHouse} whose <b>eviction policy is
 * not chosen but evolved</b>: csrbt-experimental's {@link CacheEvolutionLoop} runs its
 * founders → shadows → viability gate → (μ+λ) selection → gated promotion protocol against
 * the live access pattern, and Brine serves values out of whatever champion is currently on
 * the throne. This is the experimental module's first production consumer — the publication
 * trigger ADR-013 §4 has been holding for.
 *
 * <h2>Division of labor</h2>
 * The loop decides <em>which keys deserve residency</em> (policy, over dense int ids Brine
 * assigns per key); Brine keeps the <em>values</em> ({@code key → V}) and trims its value map
 * to the champion's actual residency ({@code loop.resident(id)} — the seam this consumer
 * named) whenever it overflows capacity. A policy hit with no cached value (invalidated, or
 * residency inherited by a freshly promoted champion) is just a store read that re-warms.
 *
 * <h2>Coherence rides the log</h2>
 * One tail subscription invalidates on every committed mutation — the log tells the cache
 * what changed, nothing is guessed. A tail gap drops the whole cache (can't know what was
 * missed — honest, and merely a cold start, never wrong data). Between a write committing and
 * its tail event arriving there is a bounded staleness window — the cost of reading off-lock,
 * documented rather than hidden.
 *
 * <p>Caller-cadenced like every control loop in the ring: generations turn over every
 * {@code windowOps} lookups on the caller's own thread; no clock, no threads of Brine's own
 * (the tail thread belongs to SmokeHouse). Seeded and deterministic: same seed + same access
 * sequence ⇒ same champion lineage.</p>
 */
public final class Brine<K, V> implements Closeable {

    /** Default founders: probation-heavy, balanced, and protection-heavy segmented LRU. */
    public static final List<CacheGenome> DEFAULT_FOUNDERS = List.of(
            CacheGenome.of(2, 1), CacheGenome.of(5, 2), CacheGenome.of(8, 3));

    /** One reading of the cache's vitals. */
    public record Stats(long gets, long valueHits, long storeReads, long invalidations,
                        int residentValues, CacheGenome champion) { }

    private final SmokeHouse<K, V> store;
    private final CacheEvolutionLoop loop;
    private final int capacity;
    private final int windowOps;
    private final Map<K, Integer> idOf = new HashMap<>();
    private final Map<K, V> resident = new HashMap<>();
    private final AutoCloseable tailHandle;

    private long gets;
    private long valueHits;
    private long storeReads;
    private long invalidations;
    private int opsInWindow;
    private CacheEvolutionLoop.GenerationResult lastVerdict;

    private Brine(SmokeHouse<K, V> store, int capacity, int windowOps,
                  List<CacheGenome> founders, MorphPolicy policy, long seed) {
        this.store = store;
        this.capacity = capacity;
        this.windowOps = windowOps;
        this.loop = new CacheEvolutionLoop(capacity, founders, 2, 3, policy, seed);
        this.loop.beginGeneration();
        this.tailHandle = store.tail(store.tailSequence(), new TailListener<K, V>() {
            @Override
            public void onEvent(TailEvent<K, V> event) {
                invalidate(event.key());
            }

            @Override
            public void onGap() {
                dropAll();
            }
        });
    }

    /** A brine over {@code store} with the default founders and promotion gates. */
    public static <K, V> Brine<K, V> over(SmokeHouse<K, V> store, int capacity,
                                          int windowOps, long seed) {
        return over(store, capacity, windowOps, DEFAULT_FOUNDERS, MorphPolicy.defaults(), seed);
    }

    /** Full control over founders and the promotion gate (tests use short cooldowns). */
    public static <K, V> Brine<K, V> over(SmokeHouse<K, V> store, int capacity, int windowOps,
                                          List<CacheGenome> founders, MorphPolicy policy,
                                          long seed) {
        Objects.requireNonNull(store, "store");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity " + capacity + " < 1");
        }
        if (windowOps < 1) {
            throw new IllegalArgumentException("windowOps " + windowOps + " < 1");
        }
        return new Brine<>(store, capacity, windowOps,
                Objects.requireNonNull(founders, "founders"),
                Objects.requireNonNull(policy, "policy"), seed);
    }

    /**
     * Read through the cache: a policy hit with a live value is served from memory; anything
     * else reads the store and re-warms if the champion holds the key. Returns {@code null}
     * exactly when the store would.
     */
    public synchronized V get(K key) throws IOException {
        Objects.requireNonNull(key, "key");
        gets++;
        int id = idOf.computeIfAbsent(key, k -> idOf.size());
        boolean policyHit = loop.lookup(id);
        V cached = resident.get(key);
        if (policyHit && cached != null) {
            valueHits++;
            tickWindow();
            return cached;
        }
        storeReads++;
        V fresh = store.get(key);
        if (fresh != null && loop.resident(id)) {
            resident.put(key, fresh);
            trimToChampion();
        } else if (fresh == null) {
            resident.remove(key);
        }
        tickWindow();
        return fresh;
    }

    /** Drop values the champion no longer holds — runs only when the map overflows. */
    private void trimToChampion() {
        if (resident.size() <= capacity) {
            return;
        }
        Iterator<Map.Entry<K, V>> it = resident.entrySet().iterator();
        while (it.hasNext()) {
            K key = it.next().getKey();
            Integer id = idOf.get(key);
            if (id == null || !loop.resident(id)) {
                it.remove();
            }
        }
    }

    /** Turn the generation over every {@code windowOps} lookups — caller-cadenced evolution. */
    private void tickWindow() {
        if (++opsInWindow >= windowOps) {
            lastVerdict = loop.endGeneration(opsInWindow);
            opsInWindow = 0;
            loop.beginGeneration();
        }
    }

    private synchronized void invalidate(K key) {
        invalidations++;
        resident.remove(key);
    }

    private synchronized void dropAll() {
        invalidations += resident.size();
        resident.clear();
    }

    /** The genome currently serving — the evolution loop's champion. */
    public synchronized CacheGenome champion() {
        return loop.champion();
    }

    /** The most recent generation's verdict, once a full window has elapsed. */
    public synchronized Optional<CacheEvolutionLoop.GenerationResult> lastVerdict() {
        return Optional.ofNullable(lastVerdict);
    }

    /** Current vitals, one consistent reading. */
    public synchronized Stats stats() {
        return new Stats(gets, valueHits, storeReads, invalidations,
                resident.size(), loop.champion());
    }

    /** Unsubscribe from the tail. The store itself is not closed. */
    @Override
    public void close() {
        try {
            tailHandle.close();
        } catch (Exception e) {
            throw new IllegalStateException("closing Brine's tail handle", e);
        }
    }
}
