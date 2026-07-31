package se.deversity.asynctest.diagnostics;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Detects {@code synchronized} blocks that lock on cached boxed primitives or on
 * JEP 390 <em>value-based classes</em>.
 *
 * <p>The JVM caches commonly-used boxed values:
 * <ul>
 *   <li>{@link Integer} and {@link Long} in the range {@code -128} to {@code 127}.</li>
 *   <li>{@link Boolean#TRUE} and {@link Boolean#FALSE}.</li>
 *   <li>Interned {@link String} literals (e.g. {@code "lock"}).</li>
 * </ul>
 * Because these are <em>identity-shared</em> instances, any code anywhere in the JVM
 * that synchronizes on the same value shares the lock — even across unrelated classes.
 * This causes surprising contention, deadlocks, or over-broad mutual exclusion.
 *
 * <p>In addition, this detector flags synchronization on the JDK's documented
 * <a href="https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/doc-files/ValueBased.html">value-based classes</a>:
 * <ul>
 *   <li>{@link Optional}, {@link OptionalInt}, {@link OptionalLong}, {@link OptionalDouble}.</li>
 *   <li>{@link Instant}, {@link LocalDate}, {@link LocalTime}, {@link LocalDateTime},
 *       {@link ZonedDateTime}, {@link OffsetDateTime}, {@link OffsetTime}, {@link Duration},
 *       {@link Period}, {@link Year}, {@link YearMonth}, {@link MonthDay}, and
 *       {@link ZoneOffset} (note: {@link java.time.ZoneId} itself is <em>not</em> value-based).</li>
 *   <li>{@link Runtime.Version}.</li>
 *   <li>{@link ProcessHandle} implementations (checked via {@code instanceof}, since it is
 *       an interface).</li>
 *   <li>The immutable collections returned by {@code List.of()}, {@code Set.of()}, and
 *       {@code Map.of()} — detected cheaply and reliably by their implementation class name
 *       prefix ({@code java.util.ImmutableCollections$}), since the classes themselves are
 *       package-private and cannot be referenced directly.</li>
 * </ul>
 * Value-based instances may be cached, interned, or — under a future Project Valhalla
 * runtime — become identity-less value objects entirely. {@code javac} already emits a
 * warning for {@code synchronized} on such types, and doing so may throw at runtime in a
 * future JDK release.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.boxedPrimitiveLockDetector();
 * Integer lockObj = 42; // cached — dangerous!
 * d.recordLockAcquire(lockObj, Thread.currentThread(), "MyService.process:30");
 * synchronized (lockObj) { ... }
 * }</pre>
 *
 * @since 0.10.0
 */
public class BoxedPrimitiveLockDetector {

    private static class LockEvent {
        final String threadName;
        final String location;
        final String reason;
        final boolean valueBased;

        LockEvent(String tname, String location, String reason, boolean valueBased) {
            this.threadName = tname;
            this.location   = location;
            this.reason     = reason;
            this.valueBased = valueBased;
        }
    }

    /**
     * JEP 390 documented value-based classes whose exact runtime type can be matched
     * directly (all are {@code final}). Populated once at class initialization and
     * never mutated afterward — safe to publish and read from multiple threads.
     */
    private static final Set<Class<?>> VALUE_BASED_TYPES = Set.of(
            Optional.class, OptionalInt.class, OptionalLong.class, OptionalDouble.class,
            Instant.class, LocalDate.class, LocalTime.class, LocalDateTime.class,
            ZonedDateTime.class, OffsetDateTime.class, OffsetTime.class, Duration.class,
            Period.class, Year.class, YearMonth.class, MonthDay.class, ZoneOffset.class,
            Runtime.Version.class);

    private final List<LockEvent> events = new CopyOnWriteArrayList<>();

    /**
     * Records a {@code synchronized} lock acquisition attempt.
     *
     * <p>If the lock object is a cached boxed primitive or a JEP 390 value-based class
     * this event will appear in the analysis report.
     *
     * @param lockObject the monitor object (null-safe)
     * @param thread     the locking thread (null-safe)
     * @param location   human-readable location, e.g. {@code "ClassName.method:lineNum"}
     */
    public void recordLockAcquire(Object lockObject, Thread thread, String location) {
        if (lockObject == null || thread == null) return;
        String reason = detectCachedPrimitive(lockObject);
        boolean valueBased = false;
        if (reason == null) {
            reason = detectValueBasedClass(lockObject);
            valueBased = reason != null;
        }
        if (reason != null) {
            events.add(new LockEvent(thread.getName(),
                    location != null ? location : "unknown", reason, valueBased));
        }
    }

    @SuppressFBWarnings("ES_COMPARING_STRINGS_WITH_EQ")
    // Intentional reference comparison: obj == obj.intern() is true only when obj is an interned
    // (literal) String — which is exactly what we want to detect as a dangerous lock target.
    @SuppressWarnings("PMD.CompareObjectsWithEquals") // reference equality with intern() is intentional to detect interned strings
    private static String detectCachedPrimitive(Object obj) {
        if (obj instanceof Boolean) {
            return "Boolean cached instance (" + obj + ")";
        }
        if (obj instanceof Integer v) {
            if (v >= -128 && v <= 127) return "cached Integer(" + v + ")";
        }
        if (obj instanceof Long v) {
            if (v >= -128 && v <= 127) return "cached Long(" + v + ")";
        }
        if (obj instanceof String && obj == ((String) obj).intern()) {
            return "interned String(\"" + obj + "\")";
        }
        return null;
    }

    /**
     * Detects synchronization on a JEP 390 value-based class instance.
     *
     * <p>Covers the documented value-based classes in {@code java.util} and {@code
     * java.time}, {@link Runtime.Version}, the {@link ProcessHandle} interface (matched
     * via {@code instanceof} since it has no concrete public type), and the immutable
     * collections produced by {@code List.of()}/{@code Set.of()}/{@code Map.of()} (matched
     * by their package-private implementation class name prefix, the only cheap and
     * reliable way to recognize them from outside {@code java.util}).
     *
     * @param obj the candidate lock object
     * @return a human-readable reason, or {@code null} if not a value-based class
     */
    private static String detectValueBasedClass(Object obj) {
        Class<?> cls = obj.getClass();
        if (VALUE_BASED_TYPES.contains(cls)) {
            return "value-based " + cls.getSimpleName() + " instance";
        }
        if (obj instanceof ProcessHandle) {
            return "value-based ProcessHandle instance";
        }
        if (cls.getName().startsWith("java.util.ImmutableCollections$")) {
            return "value-based immutable collection (" + cls.getSimpleName() + ")";
        }
        return null;
    }

    /** {@return report of synchronizations on cached boxed primitives or value-based classes} */
    public BoxedPrimitiveLockReport analyze() {
        BoxedPrimitiveLockReport r = new BoxedPrimitiveLockReport();
        for (LockEvent e : events) {
            String template = e.valueBased
                    ? "Thread '%s' synchronized on %s at [%s] — "
                            + "this is a JEP 390 value-based class; its instances may be cached, interned, "
                            + "or replaced by identity-less value objects under a future Valhalla runtime, "
                            + "so synchronizing on it is unreliable and unsupported"
                    : "Thread '%s' synchronized on %s at [%s] — "
                            + "this is a JVM-global shared instance; any code using the same "
                            + "value as a lock will accidentally share your monitor";
            r.violations.add(String.format(template, e.threadName, e.reason, e.location));
            if (e.valueBased) {
                r.hasValueBasedIssues = true;
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class BoxedPrimitiveLockReport {
        final List<String> violations = new ArrayList<>();
        private boolean hasValueBasedIssues;

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("BOXED PRIMITIVE LOCK DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("  Why: Synchronizing on a boxed primitive (Integer, Long, Boolean) is dangerous because the JVM caches\n" +
                       "       commonly-used values (Integers -128 to 127, Boolean.TRUE/FALSE). Two completely unrelated code paths\n" +
                       "       that synchronize on 'Integer.valueOf(42)' acquire the same monitor object — causing accidental\n" +
                       "       coupling and potential deadlocks with code that has nothing to do with your class.\n" +
                       "  Fix: Always synchronize on a dedicated private final Object lock = new Object(); — never on a boxed\n" +
                       "       primitive, String literal, or any other object that might be shared or interned by the JVM.");
            if (hasValueBasedIssues) {
                sb.append("\n  Why (value-based classes): Types such as Optional, Instant, Duration, and ProcessHandle are\n" +
                           "       documented JEP 390 value-based classes — their instances may be cached, interned, or replaced\n" +
                           "       entirely by identity-less value objects under a future Valhalla runtime; javac already emits a\n" +
                           "       warning for synchronizing on them.\n" +
                           "  Fix (value-based classes): Use a dedicated private final Object lock = new Object(); or a\n" +
                           "       java.util.concurrent lock (ReentrantLock, etc.) instead of synchronizing on a value-based instance.");
            }
            return sb.toString();
        }
    }
}
