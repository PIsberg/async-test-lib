package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Detects non-atomic compound updates on {@link AtomicInteger}, {@link AtomicLong},
 * {@link AtomicReference}, and similar: using {@code get()} then {@code set()} instead of
 * {@code compareAndSet()}, silently losing concurrent updates.
 *
 * <p>The Atomic* classes guarantee per-operation atomicity, but a {@code get}/{@code compute}/
 * {@code set} sequence is still a non-atomic read-modify-write that races with other threads.
 * The correct pattern is a CAS loop, or the built-in {@code updateAndGet}/{@code getAndUpdate}.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var mon = AsyncTestContext.atomicNonAtomicUpdateMonitor();
 * int v = counter.get();
 * mon.recordGet(counter, "counter", Thread.currentThread());
 * counter.set(v + 1);   // BUG: concurrent updates from other threads are silently lost
 * mon.recordSet(counter, "counter", Thread.currentThread());
 * }</pre>
 */
public class AtomicNonAtomicUpdateDetector {

    private static class AtomicState {
        final String name;
        final Map<Long, Integer> pendingGetByThread = new ConcurrentHashMap<>();
        final AtomicInteger      nonAtomicUpdates   = new AtomicInteger();
        final List<String>       details            = new CopyOnWriteArrayList<>();

        AtomicState(String name) { this.name = name; }
    }

    private final Map<Integer, AtomicState> atomics = new ConcurrentHashMap<>();

    private AtomicState stateFor(Object atomic, String name) {
        return atomics.computeIfAbsent(System.identityHashCode(atomic),
            id -> new AtomicState(name != null ? name : "Atomic@" + id));
    }

    /** Call after {@code atomic.get()}. */
    public void recordGet(Object atomic, String name, Thread thread) {
        if (atomic == null || thread == null) return;
        stateFor(atomic, name).pendingGetByThread.put(thread.threadId(), 1);
    }

    /**
     * Call after {@code atomic.set()} (a non-CAS write).
     * If the same thread previously called {@link #recordGet} without an intervening CAS,
     * the sequence is flagged as a lost-update race.
     */
    public void recordSet(Object atomic, String name, Thread thread) {
        if (atomic == null || thread == null) return;
        AtomicState s = stateFor(atomic, name);
        Integer pending = s.pendingGetByThread.remove(thread.threadId());
        if (pending != null) {
            s.nonAtomicUpdates.incrementAndGet();
            s.details.add(String.format(
                "Thread '%s' read %s then set it without compareAndSet — update is lost under concurrency",
                thread.getName(), s.name));
        }
    }

    /** Call after a successful {@code atomic.compareAndSet()} — clears the pending-get flag. */
    public void recordCas(Object atomic, String name, Thread thread) {
        if (atomic == null || thread == null) return;
        stateFor(atomic, name).pendingGetByThread.remove(thread.threadId());
    }

    /** {@return report of non-atomic compound updates} */
    public AtomicNonAtomicUpdateReport analyze() {
        AtomicNonAtomicUpdateReport r = new AtomicNonAtomicUpdateReport();
        for (AtomicState s : atomics.values()) {
            if (s.nonAtomicUpdates.get() > 0) {
                r.violations.add(String.format("%s: %d non-atomic get+set sequence(s) detected",
                    s.name, s.nonAtomicUpdates.get()));
                r.details.addAll(s.details);
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class AtomicNonAtomicUpdateReport {
        final List<String> violations = new ArrayList<>();
        final List<String> details    = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("ATOMIC NON-ATOMIC UPDATE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            for (String d : details)    sb.append("    * ").append(d).append("\n");
            sb.append("  Why: A get() followed by set() on an AtomicXxx is not atomic as a compound operation — another thread\n")
              .append("       can write a new value between the get() and the set(), and that write is silently overwritten by\n")
              .append("       the set(). The result appears correct per-thread but loses the other thread's update entirely.\n")
              .append("  Fix:\n")
              .append("    - Use ref.updateAndGet(v -> v + delta) for read-modify-write on a single field\n")
              .append("    - Use compareAndSet() in a retry loop when multiple fields must change together atomically:\n")
              .append("        do { old = ref.get(); newVal = compute(old); } while (!ref.compareAndSet(old, newVal));\n")
              .append("    - For pure counters, LongAdder gives better throughput under high contention");
            return sb.toString();
        }
    }
}
