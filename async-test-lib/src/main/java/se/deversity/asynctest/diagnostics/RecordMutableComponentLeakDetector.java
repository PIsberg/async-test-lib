package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Detects records shared across threads whose components hold mutable state, and records whose
 * component contents were observed to change while shared.
 *
 * <p>A record is only shallowly immutable. The language makes each component field {@code final},
 * which freezes the <em>reference</em> and nothing behind it. A record carrying a
 * {@code new ArrayList<>(items)} is a mutable object with a immutable-looking declaration, and
 * because records are the idiomatic way to pass data between threads, that is exactly where the
 * mistake causes damage: the receiving thread sees a value type, reasons about it as one, and is
 * wrong. {@code List.copyOf(items)} in a compact constructor closes the hole; {@code new
 * ArrayList<>(items)} does not, since the caller keeps a reference to a list the record only
 * copied once.
 *
 * <p><strong>Two findings, two trust tiers.</strong>
 * <ul>
 *   <li><strong>Observed mutation (HIGH, a verdict).</strong> The detector fingerprints every
 *       component when it first sees an instance and re-reads it at analysis time. A component
 *       whose contents changed, on a record touched by more than one thread, is a fact rather
 *       than an inference: shared mutable state was mutated during the run.</li>
 *   <li><strong>Structural risk (MEDIUM, a prompt).</strong> A shared record holding a
 *       {@code java.util.ArrayList}, a raw array or a {@code java.util.Date} is a hole whether or
 *       not anything wrote through it in this run. It is reported as something to verify, and the
 *       message names the component and the fix.</li>
 * </ul>
 *
 * <p>Components holding {@code java.util.concurrent} types are deliberately not reported. They
 * are mutable, but they are also the correct answer when mutable shared state is genuinely
 * wanted, and flagging them would train users to ignore this detector.
 *
 * <p>Usage:
 * <pre>{@code
 * record Order(String id, List<Item> items) { }
 *
 * var d = AsyncTestContext.recordMutableComponentLeakDetector();
 * d.recordShared(order, "order", Thread.currentThread());   // from each thread that touches it
 * }</pre>
 *
 * @since 1.8.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
    note = "Per-instance state in ConcurrentHashMap keyed on identity hash; the first-sight "
        + "fingerprint map is populated once under computeIfAbsent and read-only afterwards; "
        + "thread sets are ConcurrentHashMap.newKeySet(); tracking is capped by MAX_INSTANCES "
        + "with a LongAdder drop counter.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/RecordMutableComponentLeakDetectorTest.java"
)
public final class RecordMutableComponentLeakDetector {

    /** Cap on tracked record instances, so a test allocating in a loop cannot exhaust the heap. */
    static final int MAX_INSTANCES = 512;

    /** Package prefixes whose types are safe to share and mutate on purpose. */
    private static final String CONCURRENT_PREFIX = "java.util.concurrent.";

    /** Runtime classes that are immutable views, so a component holding one is already closed. */
    private static final Set<String> IMMUTABLE_VIEWS = Set.of(
            "java.util.ImmutableCollections",
            "java.util.Collections$Unmodifiable",
            "java.util.Collections$Empty",
            "java.util.Collections$Singleton");

    /** Common JDK types that are mutable and routinely mistaken for value types. */
    private static final Set<String> KNOWN_MUTABLE = Set.of(
            "java.util.Date",
            "java.util.GregorianCalendar",
            "java.util.Calendar",
            "java.text.SimpleDateFormat",
            "java.lang.StringBuilder",
            "java.lang.StringBuffer",
            "java.util.BitSet");

    private static final class State {
        final String label;
        final Class<?> type;
        final Object instance;
        final Set<Long>   threadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> threadNames = ConcurrentHashMap.newKeySet();
        /** Component name to first-sight fingerprint; {@code null} value means "not fingerprintable". */
        final Map<String, String> firstSight = new LinkedHashMap<>();
        State(String label, Object instance) {
            this.label    = label;
            this.instance = instance;
            this.type     = instance.getClass();
        }
    }

    private final Map<Integer, State> records = new ConcurrentHashMap<>();
    private final LongAdder dropped   = new LongAdder();
    private final LongAdder nonRecord = new LongAdder();

    /**
     * Record that a record instance was touched by a thread. Call it from every thread that
     * reads or passes the record; the detector reports only instances seen by more than one.
     *
     * <p>The first call for an instance snapshots each component's contents, which is what makes
     * a later change observable rather than merely possible. Non-record arguments are counted and
     * ignored.
     *
     * @param recordInstance the record instance (null-safe)
     * @param label          human-readable label for triage (may be {@code null})
     * @param thread         the touching thread
     */
    public void recordShared(@Nullable Object recordInstance, @Nullable String label,
                             @Nullable Thread thread) {
        if (recordInstance == null || thread == null) return;
        if (!recordInstance.getClass().isRecord()) {
            nonRecord.increment();
            return;
        }
        int id = System.identityHashCode(recordInstance);
        State s = records.get(id);
        if (s == null) {
            if (records.size() >= MAX_INSTANCES) {
                dropped.increment();
                return;
            }
            final String lbl = label != null
                    ? label
                    : recordInstance.getClass().getSimpleName() + "@" + id;
            s = records.computeIfAbsent(id, k -> {
                State fresh = new State(lbl, recordInstance);
                snapshot(fresh);
                return fresh;
            });
        }
        s.threadIds.add(thread.threadId());
        s.threadNames.add(thread.getName());
    }

    /** Populate the first-sight fingerprints. Called once, inside computeIfAbsent. */
    private static void snapshot(State s) {
        for (RecordComponent rc : s.type.getRecordComponents()) {
            s.firstSight.put(rc.getName(), fingerprint(read(s.instance, rc)));
        }
    }

    /**
     * {@return the value of {@code rc} on {@code instance}, or {@code null} if it cannot be read}
     *
     * <p>The {@code setAccessible} call is what makes this detector work on a real consumer's
     * types. A record declared inside a test class, or anywhere else not exported to this
     * library, has an accessor that {@code invoke} rejects - and every read then returned
     * {@code null}, so no component looked mutable, no component looked changed, and the
     * detector reported nothing at all for a record two threads were visibly sharing. The
     * library's own tests could not see it, because a record declared beside the detector is
     * accessible without this.
     *
     * <p>The suppression is bounded and cheap: this runs once per component per instance, and
     * only ever widens access to a record's own generated accessor, which has no side effects.
     * Under a module system that refuses, {@code InaccessibleObjectException} is caught below
     * and the previous behaviour stands.
     */
    private static @Nullable Object read(Object instance, RecordComponent rc) {
        try {
            java.lang.reflect.Method accessor = rc.getAccessor();
            if (!accessor.canAccess(instance)) {
                accessor.setAccessible(true);
            }
            return accessor.invoke(instance);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;   // genuinely inaccessible: skipped rather than guessed at
        }
    }

    /**
     * A cheap content digest, used only to answer "did this change?". Returns {@code null} for
     * values whose contents cannot be summarised, which suppresses the observed-mutation check
     * for that component rather than producing a guess.
     */
    private static @Nullable String fingerprint(@Nullable Object v) {
        if (v == null) return "null";
        try {
            Class<?> c = v.getClass();
            if (c.isArray())            return "arr:" + Array.getLength(v) + ':' + deepHash(v);
            if (v instanceof Collection<?> col) return "col:" + col.size() + ':' + v.hashCode();
            if (v instanceof Map<?, ?> map)     return "map:" + map.size() + ':' + v.hashCode();
            if (v instanceof StringBuilder || v instanceof StringBuffer) return "cs:" + v;
            if (KNOWN_MUTABLE.contains(c.getName())) return "mut:" + v.hashCode();
            return null;    // immutable or unknown: nothing useful to compare
        } catch (RuntimeException e) {
            return null;    // a hashCode() that throws must not take the detector down
        }
    }

    /**
     * Content hash for an array of any component type, including primitive arrays.
     * {@code Arrays.deepToString} is the one JDK helper that renders every array kind, so
     * wrapping the value in a one-element {@code Object[]} covers {@code int[]} and
     * {@code String[][]} alike without a switch over nine primitive types.
     */
    private static int deepHash(Object array) {
        return java.util.Arrays.deepToString(new Object[]{array}).hashCode();
    }

    /**
     * Evaluate the observed state and produce a report. Idempotent for a quiescent object graph:
     * the comparison is against the first-sight snapshot, which never changes, so repeated calls
     * on unchanged records yield identical reports.
     *
     * @return the report of observed mutations and structurally mutable components
     */
    public Report analyze() {
        Report r = new Report();
        for (State s : records.values()) {
            if (s.threadIds.size() < 2) continue;    // not shared: nothing this detector can claim

            List<String> mutated    = new ArrayList<>();
            List<String> structural = new ArrayList<>();

            for (RecordComponent rc : s.type.getRecordComponents()) {
                Object value = read(s.instance, rc);
                String before = s.firstSight.get(rc.getName());
                String now    = fingerprint(value);

                if (before != null && now != null && !Objects.equals(before, now)) {
                    mutated.add(String.format("'%s' (%s) changed contents while shared",
                            rc.getName(), describe(value, rc)));
                } else if (isMutable(value)) {
                    structural.add(String.format("'%s' holds %s",
                            rc.getName(), describe(value, rc)));
                }
            }

            if (!mutated.isEmpty()) {
                add(r, s, IssueSeverity.HIGH, String.format(
                    "HIGH: record '%s' (%s) was touched by %d threads (%s) and %d of its "
                    + "components were mutated during the run: %s. A record's fields are final, "
                    + "but the objects they point at are not — this is shared mutable state "
                    + "wearing a value type's clothes, and every reader of the record saw a "
                    + "different thing depending on when it looked.",
                    s.label, s.type.getSimpleName(), s.threadIds.size(),
                    String.join(", ", s.threadNames), mutated.size(),
                    String.join("; ", mutated)));
            }
            if (!structural.isEmpty()) {
                add(r, s, IssueSeverity.MEDIUM, String.format(
                    "MEDIUM: record '%s' (%s) is shared by %d threads and carries mutable "
                    + "component(s): %s. Nothing wrote through them during this run, so this is a "
                    + "hole rather than a confirmed defect — but any holder of the original "
                    + "collection can still mutate what the record exposes.",
                    s.label, s.type.getSimpleName(), s.threadIds.size(),
                    String.join("; ", structural)));
            }
        }

        long drops = dropped.sum();
        if (drops > 0 && r.hasIssues()) {
            r.violations.add(String.format(
                "NOTE: the %d-instance tracking cap was reached; %d further record instance(s) "
                + "were not examined. The findings above are a lower bound.",
                MAX_INSTANCES, drops));
        }
        return r;
    }

    /** Is this value's runtime type mutable in a way that matters for sharing? */
    private static boolean isMutable(@Nullable Object v) {
        if (v == null) return false;
        Class<?> c = v.getClass();
        if (c.isArray()) return true;
        String name = c.getName();
        if (name.startsWith(CONCURRENT_PREFIX)) return false;     // mutable on purpose, and safely
        for (String view : IMMUTABLE_VIEWS) {
            if (name.startsWith(view)) return false;
        }
        if (KNOWN_MUTABLE.contains(name)) return true;
        return v instanceof Collection<?> || v instanceof Map<?, ?>;
    }

    private static String describe(@Nullable Object v, RecordComponent rc) {
        if (v == null) return "null, declared " + rc.getType().getSimpleName();
        Class<?> c = v.getClass();
        if (c.isArray()) return c.getSimpleName() + " of length " + Array.getLength(v);
        String size = (v instanceof Collection<?> col) ? " of size " + col.size()
                    : (v instanceof Map<?, ?> map)     ? " of size " + map.size()
                    : "";
        return "a " + c.getName() + size;
    }

    private static void add(Report r, State s, IssueSeverity severity, String msg) {
        r.violations.add(msg);
        r.structuredViolations.add(new Violation(
                "RecordMutableComponentLeak",
                severity,
                msg,
                List.of(),
                Map.of("label", s.label,
                       "recordType", s.type.getName(),
                       "threadCount", s.threadIds.size()),
                Instant.now()));
    }

    /** Report produced by {@link #analyze()}. {@code hasIssues()} drives the SPI sweep. */
    public static final class Report {
        /** Human-readable findings, one per violation. */
        public final List<String> violations = new ArrayList<>();
        /** The same findings as machine-readable {@link Violation} records. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * Checks if any issues were detected.
         *
         * @return true if there are violations, false otherwise
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "RecordMutableComponentLeak — clean";
            StringBuilder sb = new StringBuilder("RECORD MUTABLE COMPONENT LEAK DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Copy defensively in a compact constructor, to an immutable view:\n")
              .append("        record Order(String id, List<Item> items) {\n")
              .append("            Order { items = List.copyOf(items); }\n")
              .append("        }\n")
              .append("      List.copyOf/Set.copyOf/Map.copyOf both copy and freeze; ")
              .append("new ArrayList<>(items) only copies, and the copy is still mutable.\n")
              .append("    - Clone arrays on the way in and on the way out; an array component ")
              .append("has no immutable equivalent, so the accessor must copy too.\n")
              .append("    - Prefer java.time types over java.util.Date, which is mutable.\n")
              .append("    - If the state genuinely has to change, say so: hold a ")
              .append("java.util.concurrent collection and drop the pretence that the record is ")
              .append("a value.\n");
            return sb.toString();
        }
    }
}
