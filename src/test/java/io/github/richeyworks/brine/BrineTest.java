package io.github.richeyworks.brine;

import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.experimental.cache.CacheGenome;
import io.github.richeyworks.smokehouse.SmokeHouse;
import io.github.richeyworks.smokehouse.SmokeHouseOptions;
import io.github.richeyworks.superbeefsort.external.SpillSerializer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Oracle tests in the house style: every read through the brine is checked against a
 * {@code TreeMap} reference of the store's truth. Seeded and deterministic; the only
 * timing concession is polling after writes, because invalidation rides the tail thread.
 */
class BrineTest {

    private static final long AWAIT = 5_000;

    private static SmokeHouseOptions<Long, String> opts() {
        return SmokeHouseOptions.of(SpillSerializer.forLongs(), SpillSerializer.forStrings())
                .indexTier(SmokeHouseOptions.IndexTier.STATIC);
    }

    /** Short-cooldown gate so promotions can happen at test scale. */
    private static MorphPolicy eagerGates() {
        return new MorphPolicy(64, 0.05, 1);
    }

    private static boolean eventually(Check check) throws IOException {
        long deadline = System.currentTimeMillis() + AWAIT;
        while (System.currentTimeMillis() < deadline) {
            if (check.holds()) {
                return true;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return check.holds();
    }

    private interface Check {
        boolean holds() throws IOException;
    }

    @Test
    void readThroughMatchesTheStoreAndStaysBounded(@TempDir Path dir) throws IOException {
        Random rnd = new Random(42);
        TreeMap<Long, String> oracle = new TreeMap<>();
        try (SmokeHouse<Long, String> store = SmokeHouse.open(dir, opts());
             Brine<Long, String> brine = Brine.over(store, 32, 256, 42)) {
            for (long k = 0; k < 200; k++) {
                String v = "v" + k;
                store.put(k, v);
                oracle.put(k, v);
            }
            for (int i = 0; i < 2_000; i++) {
                long k = rnd.nextInt(220);                     // some misses past the keyspace
                assertEquals(oracle.get(k), brine.get(k), "get(" + k + ")");
                assertTrue(brine.stats().residentValues() <= 32,
                        "value map must stay within capacity after trims");
            }
            Brine.Stats stats = brine.stats();
            assertEquals(2_000, stats.gets());
            assertEquals(stats.gets(), stats.valueHits() + stats.storeReads(),
                    "every get is either a value hit or a store read");
        }
    }

    @Test
    void hotKeysEndUpServedFromMemory(@TempDir Path dir) throws IOException {
        try (SmokeHouse<Long, String> store = SmokeHouse.open(dir, opts());
             Brine<Long, String> brine = Brine.over(store, 16, 128, 7)) {
            for (long k = 0; k < 8; k++) {
                store.put(k, "hot" + k);
            }
            Random rnd = new Random(7);
            for (int i = 0; i < 1_000; i++) {
                brine.get((long) rnd.nextInt(8));              // 8 hot keys, capacity 16
            }
            Brine.Stats stats = brine.stats();
            assertTrue(stats.valueHits() > stats.storeReads(),
                    "a hot working set within capacity must be served mostly from memory: " + stats);
        }
    }

    @Test
    void invalidationRidesTheTail(@TempDir Path dir) throws IOException {
        try (SmokeHouse<Long, String> store = SmokeHouse.open(dir, opts());
             Brine<Long, String> brine = Brine.over(store, 8, 64, 3)) {
            store.put(1L, "old");
            for (int i = 0; i < 8; i++) {
                brine.get(1L);                                 // warm it into residency
            }
            store.put(1L, "new");                              // the log announces the change
            assertTrue(eventually(() -> "new".equals(brine.get(1L))),
                    "the tail must invalidate the stale value");

            store.delete(1L);
            assertTrue(eventually(() -> brine.get(1L) == null),
                    "a delete must reach the cache too");
            assertNull(brine.get(1L));
            assertTrue(brine.stats().invalidations() > 0);
        }
    }

    @Test
    void evolutionTurnsGenerationsAndStaysStructurallySound(@TempDir Path dir)
            throws IOException {
        try (SmokeHouse<Long, String> store = SmokeHouse.open(dir, opts());
             Brine<Long, String> brine = Brine.over(store, 24, 100,
                     Brine.DEFAULT_FOUNDERS, eagerGates(), 11)) {
            Random rnd = new Random(11);
            for (long k = 0; k < 100; k++) {
                store.put(k, "v" + k);
            }
            for (int i = 0; i < 1_200; i++) {                  // 12 full generation windows
                long k = (long) Math.min(99, Math.abs(Math.round(rnd.nextGaussian() * 20 + 30)));
                brine.get(k);                                  // skewed traffic — something to learn
            }
            assertTrue(brine.lastVerdict().isPresent(), "12 windows must yield a verdict");
            CacheGenome champion = brine.champion();
            assertTrue(champion.protectedTenths() >= CacheGenome.TENTHS_MIN
                            && champion.protectedTenths() <= CacheGenome.TENTHS_MAX,
                    "champion stays inside the structural box: " + champion);
            assertTrue(champion.promoteAfter() >= CacheGenome.PROMOTE_MIN
                            && champion.promoteAfter() <= CacheGenome.PROMOTE_MAX);
        }
    }

    @Test
    void sameSeedSameTrafficSameChampionLineage(@TempDir Path dirA, @TempDir Path dirB)
            throws IOException {
        List<CacheGenome> championsA = run(dirA);
        List<CacheGenome> championsB = run(dirB);
        assertEquals(championsA, championsB,
                "seeded evolution over identical traffic must be reproducible");
    }

    private static List<CacheGenome> run(Path dir) throws IOException {
        List<CacheGenome> champions = new ArrayList<>();
        try (SmokeHouse<Long, String> store = SmokeHouse.open(dir, opts());
             Brine<Long, String> brine = Brine.over(store, 16, 100,
                     Brine.DEFAULT_FOUNDERS, eagerGates(), 99)) {
            for (long k = 0; k < 60; k++) {
                store.put(k, "v" + k);
            }
            Random rnd = new Random(99);
            for (int i = 0; i < 800; i++) {
                brine.get((long) rnd.nextInt(60));
                if (i % 100 == 99) {
                    champions.add(brine.champion());
                }
            }
        }
        return champions;
    }
}
