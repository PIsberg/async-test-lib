package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects a blocking call on a thread that must not block: a Reactor non-blocking scheduler thread,
 * a Netty or Vert.x event loop, or a Kotlin {@code Dispatchers.Default} worker (#991, #993).
 *
 * <p>An event loop runs every task queued for it, one after another, and a reactive scheduler's
 * non-blocking threads are few by design, one per core. A {@code Thread.sleep}, a {@code join} or
 * {@code get} of a pending future, a latch await or a take from an empty queue on such a thread
 * stalls everything behind it: the server stops answering, the pipeline stops emitting, and nothing
 * fails, it just gets slow under load. It is the defining misuse of the reactive frameworks, and
 * BlockHound exists for it. {@code Dispatchers.Default} is the same for coroutines: a CPU-bound pool
 * of one thread per core that blocking starves, where {@code Dispatchers.IO} is the place to block.
 *
 * <p>A thread is classified the way BlockHound classifies it, by its class's names, since the
 * library depends on none of the frameworks: one implementing {@code reactor.core.scheduler.NonBlocking};
 * a {@code io.vertx.core.impl.VertxThread} whose {@code isWorker()} is false; otherwise a
 * {@code io.netty.util.concurrent.FastThreadLocalThread} whose {@code permitBlockingCalls()} is
 * false; and a {@code kotlinx.coroutines.scheduling.CoroutineScheduler$Worker} whose {@code state}
 * is {@code CPU_ACQUIRED}. A coroutine worker running {@code Dispatchers.IO} work is in state
 * {@code BLOCKING}, which is that dispatcher's purpose, and is not reported.
 *
 * <p><strong>Attribution needs the framework in {@code includes=}.</strong> With the agent
 * ({@code collections=true}) every woven blocking call reaches this detector, but only on a thread
 * the run is known to own. An event-loop thread is started inside the framework, so the run sees
 * it only when the framework's own code is woven: add its scheduler packages to the agent's
 * {@code includes=} ({@code reactor.core.scheduler}, {@code io.netty.util.concurrent},
 * {@code io.vertx.core}, {@code kotlinx.coroutines}), so the woven {@code Thread.start} or executor
 * submission inside the framework carries the run to the thread. A thread the run never saw
 * reports nothing, which errs toward silence under parallel test runs; and a coroutine worker the
 * JVM created for an earlier test is that test's, not this one's.
 *
 * <p>Usage inside {@code @AsyncTest(includes = DetectorType.BLOCKING_ON_NON_BLOCKING_THREAD)}:
 * <pre>{@code
 * var d = AsyncTestContext.blockingOnNonBlockingThreadDetector();
 * d.recordBlockingCall("Thread.sleep", Thread.currentThread()); // inside an event-loop task
 * }</pre>
 *
 * @since 1.13.2
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
    note = "Findings in a concurrent set keyed by thread, kind and operation; classification reads "
        + "the calling thread's own object only.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/BlockingOnNonBlockingThreadDetectorTest.java"
)
public final class BlockingOnNonBlockingThreadDetector {

    /** At most this many distinct findings are kept; a busy event loop repeats one forever. */
    private static final int MAX_FINDINGS = 256;

    /** Distinct findings, one line each. */
    private final Set<String> findings = ConcurrentHashMap.newKeySet();

    /**
     * Records a blocking call on {@code thread}; reported if the thread is one that must not block.
     *
     * @param operation the blocking call, as the report should name it, such as {@code Thread.sleep}
     * @param thread    the thread that blocked (null-safe)
     */
    public void recordBlockingCall(String operation, Thread thread) {
        if (operation == null || thread == null || findings.size() >= MAX_FINDINGS) return;
        String kind = nonBlockingKind(thread);
        if (kind != null) {
            findings.add(String.format("Thread '%s', %s, blocked in %s", thread.getName(), kind, operation));
        }
    }

    /** {@return what kind of must-not-block thread {@code thread} is, or {@code null} for any other} */
    static @Nullable String nonBlockingKind(Thread thread) {
        Class<?> type = thread.getClass();
        if (implementsNamed(type, "reactor.core.scheduler.NonBlocking")) {
            return "a Reactor non-blocking scheduler thread";
        }
        Class<?> vertx = superclassNamed(type, "io.vertx.core.impl.VertxThread");
        if (vertx != null) {
            // A Vert.x worker is a Netty thread too; executeBlocking work belongs on it.
            return Boolean.FALSE.equals(call(vertx, "isWorker", thread)) ? "a Vert.x event-loop thread" : null;
        }
        Class<?> netty = superclassNamed(type, "io.netty.util.concurrent.FastThreadLocalThread");
        if (netty != null) {
            return Boolean.FALSE.equals(call(netty, "permitBlockingCalls", thread))
                    ? "a Netty event-loop thread" : null;
        }
        if ("kotlinx.coroutines.scheduling.CoroutineScheduler$Worker".equals(type.getName())) {
            Object state = read(type, "state", thread);
            return state != null && "CPU_ACQUIRED".equals(state.toString())
                    ? "a Kotlin Dispatchers.Default worker" : null;
        }
        return null;
    }

    private static boolean implementsNamed(Class<?> type, String name) {
        java.util.Deque<Class<?>> pending = new java.util.ArrayDeque<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            pending.addAll(List.of(c.getInterfaces()));
        }
        while (!pending.isEmpty()) {
            Class<?> iface = pending.poll();
            if (name.equals(iface.getName())) {
                return true;
            }
            pending.addAll(List.of(iface.getInterfaces()));
        }
        return false;
    }

    private static @Nullable Class<?> superclassNamed(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }

    /** {@return {@code owner.method()} called on {@code thread}, or {@code null} where it cannot be} */
    private static @Nullable Object call(Class<?> owner, String method, Thread thread) {
        try {
            return owner.getMethod(method).invoke(thread);
        } catch (ReflectiveOperationException | RuntimeException e) { // NOPMD - unreadable is not classified
            return null;
        }
    }

    /** {@return {@code owner}'s field {@code field} on {@code thread}, or {@code null} where it cannot be read} */
    private static @Nullable Object read(Class<?> owner, String field, Thread thread) {
        try {
            java.lang.reflect.Field f = owner.getDeclaredField(field);
            return f.trySetAccessible() ? f.get(thread) : null;
        } catch (ReflectiveOperationException | RuntimeException e) { // NOPMD - unreadable is not classified
            return null;
        }
    }

    /**
     * {@return report of the blocking calls on threads that must not block}
     */
    public Report analyze() {
        Report r = new Report();
        for (String finding : new java.util.TreeSet<>(findings)) {
            String line = finding + " - it stalls every task queued behind it on that thread. Move the "
                    + "call to a thread made for blocking: Schedulers.boundedElastic(), executeBlocking, "
                    + "Dispatchers.IO";
            r.violations.add(line);
            r.structuredViolations.add(new Violation("BlockingOnNonBlockingThread", IssueSeverity.HIGH,
                    line, List.of(), Map.of(), Instant.now()));
        }
        return DetectorFailurePolicy.checkedReport(this, r);
    }

    /** Report produced by {@link #analyze()}. {@code hasIssues()} drives the SPI sweep. */
    public static final class Report {
        /** Findings as human-readable lines, for the text report. */
        public final List<String> violations = new ArrayList<>();
        /** The findings as Violations, with their severity. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /** {@return whether a thread that must not block blocked} */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "BlockingOnNonBlockingThread - clean";
            StringBuilder sb = new StringBuilder("BLOCKING ON A NON-BLOCKING THREAD DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            return sb.toString();
        }
    }
}
