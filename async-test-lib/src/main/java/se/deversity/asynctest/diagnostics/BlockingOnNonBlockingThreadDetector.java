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
 * <p>A Kotlin {@code runBlocking} is the exception (#1007): {@link #recordRunBlocking} reports one
 * entered on a coroutine worker in either state, since the caller is then itself a coroutine
 * parking its thread until another finishes, and one entered by a coroutine on any other thread,
 * which it recognises by a {@code kotlinx.coroutines} frame below the call on the calling thread's
 * own stack: a {@code runBlocking} nested in another, or on an executor-backed dispatcher, whose
 * threads have no class of their own. The agent's {@code RunBlockingWeaver}
 * feeds it from every woven {@code runBlocking} call site; on an ordinary thread outside any
 * coroutine it reports nothing.
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

    /** The thread class every {@code Dispatchers.Default} and {@code Dispatchers.IO} task runs on. */
    private static final String COROUTINE_WORKER = "kotlinx.coroutines.scheduling.CoroutineScheduler$Worker";

    /** What a blocking call on one of these threads should do instead. */
    private static final String BLOCKING_REMEDY = "it stalls every task queued behind it on that thread. Move "
            + "the call to a thread made for blocking: Schedulers.boundedElastic(), executeBlocking, Dispatchers.IO";

    /** What a runBlocking inside a coroutine should do instead; Dispatchers.IO is reported too. */
    private static final String RUN_BLOCKING_REMEDY = "it parks the thread until another coroutine finishes, and "
            + "deadlocks once every thread of the dispatcher waits that way. Make the caller a suspend fun and call "
            + "the suspending code directly, or use withContext to change dispatcher";

    /** The suffix kotlinx's debug mode, on under -ea, appends to a worker's name while it runs a coroutine. */
    private static final String COROUTINE_NAME_SUFFIX = " @coroutine#";

    /**
     * The package every coroutine body is entered through ({@code DispatchedTask.run}, the event
     * loop of an enclosing {@code runBlocking}, an undispatched start): a frame from it on the calling
     * thread's stack means the caller runs inside a coroutine, whatever thread class it is on.
     */
    private static final String KOTLINX_PACKAGE = "kotlinx.coroutines.";

    /** Distinct findings, one line each, remedy included. */
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
            findings.add(String.format("Thread '%s', %s, blocked in %s - %s", nameOf(thread), kind, operation,
                    BLOCKING_REMEDY));
        }
    }

    /**
     * Records a Kotlin {@code runBlocking} entered on {@code thread} (#1007); reported on a thread
     * that must not block, and on any coroutine dispatcher worker.
     *
     * <p>{@code runBlocking} parks its thread until the coroutine it starts completes. On an
     * ordinary thread that is its purpose, bridging blocking code to suspending code. On a
     * {@code CoroutineScheduler} worker the caller is itself a coroutine: on {@code Dispatchers.Default}
     * it takes one of the per-core threads out of the pool, and on {@code Dispatchers.IO}, or a
     * {@code limitedParallelism} view of either, it holds a thread the coroutine it waits for may
     * need, which deadlocks once every thread waits that way. So a worker in either state is
     * reported, where a plain blocking call on a {@code Dispatchers.IO} worker is not.
     *
     * <p>A coroutine on any other thread is recognised only when {@code thread} is the calling
     * thread, by walking its stack for a {@code kotlinx.coroutines} frame: a {@code runBlocking}
     * nested in another runs inside the outer one's event loop on the caller's own thread, and an
     * {@code asCoroutineDispatcher} dispatcher runs its coroutines on the executor's plain threads,
     * so neither has a thread class to classify. The walk costs microseconds, and
     * {@code runBlocking} is itself a parked thread and an event loop. The executor's thread must
     * still be the run's: the woven {@code execute} hands the task over on a JDK
     * {@code ScheduledThreadPoolExecutor}, but not through the wrapper
     * {@code newSingleThreadContext} puts around its pool, so that dispatcher's thread is never
     * heard from (#1107).
     *
     * @param thread the thread that entered {@code runBlocking} (null-safe)
     * @since 1.13.3
     */
    public void recordRunBlocking(Thread thread) {
        if (thread == null || findings.size() >= MAX_FINDINGS) return;
        String kind = nonBlockingKind(thread);
        if (kind == null && COROUTINE_WORKER.equals(thread.getClass().getName())) {
            kind = "a Kotlin coroutine dispatcher worker, so inside a coroutine";
        }
        if (kind == null && thread.equals(Thread.currentThread()) && insideACoroutine()) {
            kind = "inside a coroutine (a kotlinx.coroutines frame is below the call: a runBlocking nested in "
                    + "another, or a dispatcher of its own)";
        }
        if (kind != null) {
            findings.add(String.format("Thread '%s', %s, blocked in runBlocking - %s", nameOf(thread), kind,
                    RUN_BLOCKING_REMEDY));
        }
    }

    /**
     * {@return the thread's name, without the coroutine kotlinx's debug mode appends to it}
     * Without this one thread is a new finding per coroutine it runs. Any thread class: debug mode
     * renames whatever thread a coroutine resumes on, the run's own worker inside a nested
     * {@code runBlocking} included.
     */
    private static String nameOf(Thread thread) {
        String name = thread.getName();
        int suffix = name.indexOf(COROUTINE_NAME_SUFFIX);
        return suffix > 0 ? name.substring(0, suffix) : name;
    }

    /**
     * {@return whether the calling thread is running a coroutine body}: a {@code kotlinx.coroutines}
     * frame is on its stack. Reads only the calling thread's own stack; never another thread's.
     */
    static boolean insideACoroutine() {
        return StackWalker.getInstance()
                .walk(frames -> frames.anyMatch(f -> f.getClassName().startsWith(KOTLINX_PACKAGE)));
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
        if (COROUTINE_WORKER.equals(type.getName())) {
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
        for (String line : new java.util.TreeSet<>(findings)) {
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
