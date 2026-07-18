package benchmarks;

import io.github.richeyworks.brine.Brine;
import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.experimental.cache.CacheGenome;
import io.github.richeyworks.csrbt.experimental.cache.SegmentedLruCache;
import io.github.richeyworks.smokehouse.SmokeHouse;
import io.github.richeyworks.smokehouse.SmokeHouseOptions;
import io.github.richeyworks.superbeefsort.external.SpillSerializer;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Brine's central question, made falsifiable: <b>does the evolved policy earn its overhead
 * over a fixed one?</b> Three read paths over the same on-disk store and the same
 * deterministic access pattern:
 *
 * <ul>
 *   <li>{@code evolvedBrine} — the real engine: champion + shadow mirroring + generation
 *       turnover every 4096 ops (short-cooldown gates so evolution actually happens inside
 *       the measurement window).</li>
 *   <li>{@code fixedSegmentedLru} — the honest baseline: one hand-wired
 *       {@link SegmentedLruCache} with the balanced founder genome, same read-through and
 *       trim logic, zero evolution machinery.</li>
 *   <li>{@code noCache} — the floor: every get is a store read.</li>
 * </ul>
 *
 * Two shapes, chosen so the verdict can flip: {@code STABLE_HOT} (one Gaussian working set —
 * a fixed policy should be near-optimal, so this row prices the evolution overhead) and
 * {@code SHIFTING_HOT} (the hot center jumps every 8192 accesses — this is where adapting
 * the policy could pay). If {@code evolvedBrine} can't approach {@code fixedSegmentedLru}
 * on STABLE_HOT or beat it on SHIFTING_HOT, the CLAUDE.md roadmap says so honestly.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
public class EvolvedVsFixedBenchmark {

    private static final int STORE_KEYS = 10_000;
    private static final int CAPACITY = 256;
    private static final int PATTERN = 1 << 16;
    private static final int MASK = PATTERN - 1;

    @Param({"STABLE_HOT", "SHIFTING_HOT"})
    public String shape;

    private Path dir;
    private SmokeHouse<Long, String> store;
    private Brine<Long, String> brine;
    private SegmentedLruCache fixedPolicy;
    private Map<Long, String> fixedValues;
    private long[] pattern;
    private int idxEvolved;
    private int idxFixed;
    private int idxNone;

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("brine-jmh");
        store = SmokeHouse.open(dir,
                SmokeHouseOptions.of(SpillSerializer.forLongs(), SpillSerializer.forStrings())
                        .indexTier(SmokeHouseOptions.IndexTier.STATIC));
        for (long k = 0; k < STORE_KEYS; k++) {
            store.put(k, "value-" + k);
        }
        Random rnd = new Random(42);
        pattern = new long[PATTERN];
        for (int i = 0; i < PATTERN; i++) {
            int center = "STABLE_HOT".equals(shape)
                    ? 5_000
                    : 1_000 + 2_000 * ((i / 8_192) % 5);       // the hot set jumps
            long k = Math.round(rnd.nextGaussian() * 300 + center);
            pattern[i] = Math.max(0, Math.min(STORE_KEYS - 1, k));
        }
        brine = Brine.over(store, CAPACITY, 4_096,
                Brine.DEFAULT_FOUNDERS, new MorphPolicy(2_048, 0.05, 2), 42);
        fixedPolicy = new SegmentedLruCache(CAPACITY, CacheGenome.of(5, 2));
        fixedValues = new HashMap<>();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        brine.close();
        store.close();
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    // ── The rows ─────────────────────────────────────────────────────────────────

    /** The real engine: evolved policy, shadow mirroring, generation turnover included. */
    @Benchmark
    public String evolvedBrine() throws IOException {
        return brine.get(pattern[idxEvolved++ & MASK]);
    }

    /** The honest baseline: one fixed genome, identical read-through and trim logic. */
    @Benchmark
    public String fixedSegmentedLru() throws IOException {
        long key = pattern[idxFixed++ & MASK];
        int id = (int) key;
        boolean hit = fixedPolicy.get(id);
        if (!hit) {
            fixedPolicy.admit(id);
        }
        String cached = fixedValues.get(key);
        if (hit && cached != null) {
            return cached;
        }
        String fresh = store.get(key);
        if (fresh != null && fixedPolicy.peek(id)) {
            fixedValues.put(key, fresh);
            if (fixedValues.size() > CAPACITY) {
                Iterator<Map.Entry<Long, String>> it = fixedValues.entrySet().iterator();
                while (it.hasNext()) {
                    if (!fixedPolicy.peek((int) (long) it.next().getKey())) {
                        it.remove();
                    }
                }
            }
        }
        return fresh;
    }

    /** The floor: no cache, every get hits the store. */
    @Benchmark
    public String noCache() throws IOException {
        return store.get(pattern[idxNone++ & MASK]);
    }
}
