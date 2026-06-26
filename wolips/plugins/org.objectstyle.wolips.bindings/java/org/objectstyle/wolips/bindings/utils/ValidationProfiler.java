package org.objectstyle.wolips.bindings.utils;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight, opt-in profiler for component binding validation.
 *
 * <p>This exists so that each performance fix to the validation pipeline can be
 * verified against before/after numbers. It is <b>disabled by default</b> and
 * has effectively zero overhead when off: every instrumentation call is guarded
 * by a single volatile boolean read and returns immediately.</p>
 *
 * <p>Enable it either by launching the workbench with
 * <code>-Dwolips.validation.profile=true</code>, or at runtime via
 * {@link #setEnabled(boolean)}. When enabled, a summary of the metrics gathered
 * during each validation pass is printed to {@code System.out} when the pass
 * ends (see {@link #beginPass(String)} / {@link #endPass(String, long)}).</p>
 *
 * <p>Metrics are global atomic counters; each pass snapshots them on entry and
 * reports the delta on exit. Validation can run on a background job, so if two
 * components validate concurrently their deltas may overlap -- for the intended
 * use (interactively profiling one component at a time) this is not a concern,
 * but the per-pass wall-clock time is always exact regardless.</p>
 *
 * @author wolips
 */
public final class ValidationProfiler {
  /** Metric names. Grouped so each maps onto a specific proposed fix. */
  // --- whole-pass shape ---
  public static final String ELEMENT = "elements";
  public static final String BINDING = "bindings";

  // --- fix #1: BindingValueKeyPath is built twice per binding (deprecation re-resolution) ---
  public static final String KEYPATH_CONSTRUCT = "BindingValueKeyPath.construct";

  // --- the "DEVIL": JDT type resolution (TypeCache.resolveType) ---
  public static final String GET_TYPE_FOR_NAME = "getTypeForName.call";
  public static final String GET_TYPE_FOR_NAME_HIT = "getTypeForName.hit";
  public static final String GET_TYPE_FOR_NAME_MISS = "getTypeForName.miss";
  public static final String RESOLVE_TYPE = "resolveType";

  // --- fix #2: binding keys are never cached for generic types ---
  public static final String ACCESSOR_KEYS = "accessorKeys.call";
  public static final String ACCESSOR_KEYS_HIT = "accessorKeys.hit";
  public static final String ACCESSOR_KEYS_MISS = "accessorKeys.miss";
  public static final String ACCESSOR_KEYS_GENERIC_SKIP = "accessorKeys.genericNotCached";
  public static final String MUTATOR_KEYS = "mutatorKeys.call";
  public static final String MUTATOR_KEYS_HIT = "mutatorKeys.hit";
  public static final String MUTATOR_KEYS_MISS = "mutatorKeys.miss";
  public static final String MUTATOR_KEYS_GENERIC_SKIP = "mutatorKeys.genericNotCached";

  // --- the underlying reflection scan over fields/methods ---
  public static final String GET_BINDING_KEYS = "getBindingKeys";

  // --- element type lookup (workspace search, cached in ApiCache) ---
  public static final String FIND_ELEMENT_TYPE = "findElementType.call";
  public static final String FIND_ELEMENT_TYPE_HIT = "findElementType.hit";
  public static final String FIND_ELEMENT_TYPE_SEARCH = "findElementType.search";

  // --- fix #4: preference re-reads per element/binding ---
  // PREFERENCE_READ counts actual preference-store reads (cache misses);
  // PREFERENCE_CACHE_HIT counts reads served from the memoized cache. Before
  // the fix every logical read hit the store; after it, reads collapse to one
  // per distinct key until a preference changes.
  public static final String PREFERENCE_READ = "preferenceStoreRead";
  public static final String PREFERENCE_CACHE_HIT = "preferenceCacheHit";

  private static final class Counter {
    final LongAdder count = new LongAdder();
    final LongAdder nanos = new LongAdder();
  }

  private static final ConcurrentHashMap<String, Counter> METRICS = new ConcurrentHashMap<String, Counter>();

  public static volatile boolean enabled = Boolean.getBoolean("wolips.validation.profile");

  private ValidationProfiler() {
    // utility class
  }

  public static boolean isEnabled() {
    return enabled;
  }

  public static void setEnabled(boolean newEnabled) {
    enabled = newEnabled;
  }

  private static Counter counter(String metric) {
    Counter counter = METRICS.get(metric);
    if (counter == null) {
      counter = new Counter();
      Counter existing = METRICS.putIfAbsent(metric, counter);
      if (existing != null) {
        counter = existing;
      }
    }
    return counter;
  }

  /** Increment a pure call/event counter. */
  public static void count(String metric) {
    if (!enabled) {
      return;
    }
    counter(metric).count.increment();
  }

  /** Add {@code n} to a pure call/event counter. */
  public static void count(String metric, long n) {
    if (!enabled) {
      return;
    }
    counter(metric).count.add(n);
  }

  /**
   * Returns {@link System#nanoTime()} when enabled, otherwise 0. Pair with
   * {@link #add(String, long)} so the {@code nanoTime()} call itself is skipped
   * when profiling is off.
   */
  public static long now() {
    return enabled ? System.nanoTime() : 0L;
  }

  /** Record one timed occurrence of a metric, where {@code startNanos} came from {@link #now()}. */
  public static void add(String metric, long startNanos) {
    if (!enabled) {
      return;
    }
    Counter counter = counter(metric);
    counter.count.increment();
    counter.nanos.add(System.nanoTime() - startNanos);
  }

  private static Map<String, long[]> snapshot() {
    Map<String, long[]> snapshot = new TreeMap<String, long[]>();
    for (Map.Entry<String, Counter> entry : METRICS.entrySet()) {
      Counter counter = entry.getValue();
      snapshot.put(entry.getKey(), new long[] { counter.count.sum(), counter.nanos.sum() });
    }
    return snapshot;
  }

  /**
   * Marks the start of a validation pass. Returns the wall-clock start time to
   * be handed back to {@link #endPass(String, long)}, or 0 when disabled.
   */
  public static long beginPass(String label) {
    if (!enabled) {
      return 0L;
    }
    PASS_BASELINE.set(snapshot());
    return System.nanoTime();
  }

  private static final ThreadLocal<Map<String, long[]>> PASS_BASELINE = new ThreadLocal<Map<String, long[]>>();

  /** Marks the end of a validation pass and prints the delta summary. */
  public static void endPass(String label, long startNanos) {
    if (!enabled) {
      return;
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    Map<String, long[]> baseline = PASS_BASELINE.get();
    PASS_BASELINE.remove();
    Map<String, long[]> end = snapshot();

    StringBuilder sb = new StringBuilder();
    sb.append("\n========== validation pass: ").append(label).append(" ==========\n");
    sb.append(String.format("  %-34s %12.2f ms%n", "TOTAL (wall clock)", elapsedNanos / 1_000_000.0));
    sb.append(String.format("  %-34s %12s %12s%n", "metric", "count", "ms"));
    for (Map.Entry<String, long[]> entry : end.entrySet()) {
      String metric = entry.getKey();
      long[] endValue = entry.getValue();
      long[] baseValue = baseline == null ? null : baseline.get(metric);
      long deltaCount = endValue[0] - (baseValue == null ? 0 : baseValue[0]);
      long deltaNanos = endValue[1] - (baseValue == null ? 0 : baseValue[1]);
      if (deltaCount == 0 && deltaNanos == 0) {
        continue;
      }
      if (deltaNanos == 0) {
        sb.append(String.format("  %-34s %12d %12s%n", metric, deltaCount, "-"));
      }
      else {
        sb.append(String.format("  %-34s %12d %12.2f%n", metric, deltaCount, deltaNanos / 1_000_000.0));
      }
    }
    sb.append("==================================================");
    System.out.println(sb.toString());
  }

  /** Clears all accumulated metrics. Useful before a controlled A/B run. */
  public static void reset() {
    METRICS.clear();
  }
}
