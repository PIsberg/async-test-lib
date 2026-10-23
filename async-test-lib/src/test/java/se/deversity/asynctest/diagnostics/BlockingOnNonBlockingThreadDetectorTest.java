package se.deversity.asynctest.diagnostics;

import io.netty.util.concurrent.FastThreadLocalThread;
import io.vertx.core.impl.VertxThread;
import kotlinx.coroutines.scheduling.CoroutineScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.NonBlocking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A blocking call on a thread that must not block (#991, #993), classified the way BlockHound
 * classifies it. The framework types are test stand-ins carrying the real class names, since the
 * detector reads names and the library depends on none of the frameworks.
 */
class BlockingOnNonBlockingThreadDetectorTest {

    /** A Reactor non-blocking scheduler thread, as Schedulers.parallel() makes them. */
    static final class ReactorThread extends Thread implements NonBlocking {
        ReactorThread(Runnable task) {
            super(task, "parallel-1");
        }
    }

    /** Records a sleep on {@code thread}; the classification reads the thread object, not its run. */
    private static BlockingOnNonBlockingThreadDetector.Report blockOn(Thread thread) {
        BlockingOnNonBlockingThreadDetector detector = new BlockingOnNonBlockingThreadDetector();
        detector.recordBlockingCall("Thread.sleep", thread);
        return detector.analyze();
    }

    @Test
    @DisplayName("a Reactor non-blocking thread that blocks is reported")
    void reactorNonBlockingThreadIsReported() {
        BlockingOnNonBlockingThreadDetector.Report report = blockOn(new ReactorThread(() -> { }));
        assertTrue(report.hasIssues(), report.toString());
        assertTrue(report.toString().contains("Reactor") && report.toString().contains("Thread.sleep"),
                report.toString());
        assertEquals(1, report.structuredViolations.size());
    }

    @Test
    @DisplayName("a Netty event loop that does not permit blocking is reported, one that does is not")
    void nettyEventLoopFollowsItsPermit() {
        assertTrue(blockOn(new FastThreadLocalThread(() -> { }, false)).hasIssues());
        assertFalse(blockOn(new FastThreadLocalThread(() -> { }, true)).hasIssues());
    }

    @Test
    @DisplayName("a Vert.x event loop is reported and a Vert.x worker, also a Netty thread, is not")
    void vertxWorkerIsNotAnEventLoop() {
        assertTrue(blockOn(new VertxThread(() -> { }, false)).toString().contains("Vert.x"));
        assertFalse(blockOn(new VertxThread(() -> { }, true)).hasIssues(),
                "a Vert.x worker runs executeBlocking work, which is where blocking belongs");
    }

    @Test
    @DisplayName("a Kotlin Dispatchers.Default worker is reported, the same worker on Dispatchers.IO is not (#993)")
    void kotlinDefaultDispatcherIsReportedAndIoIsNot() {
        assertTrue(blockOn(new CoroutineScheduler.Worker(() -> { },
                CoroutineScheduler.WorkerState.CPU_ACQUIRED)).toString().contains("Dispatchers.Default"));
        assertFalse(blockOn(new CoroutineScheduler.Worker(() -> { },
                CoroutineScheduler.WorkerState.BLOCKING)).hasIssues(),
                "Dispatchers.IO shares the worker pool and marks the worker BLOCKING, so blocking is its job");
    }

    /** Records a {@code runBlocking} entered on {@code thread}. */
    private static BlockingOnNonBlockingThreadDetector.Report runBlockingOn(Thread thread) {
        BlockingOnNonBlockingThreadDetector detector = new BlockingOnNonBlockingThreadDetector();
        detector.recordRunBlocking(thread);
        return detector.analyze();
    }

    @Test
    @DisplayName("runBlocking on a coroutine worker is reported on Dispatchers.Default and IO alike (#1007)")
    void runBlockingOnAnyCoroutineWorkerIsReported() {
        BlockingOnNonBlockingThreadDetector.Report onDefault = runBlockingOn(new CoroutineScheduler.Worker(
                () -> { }, CoroutineScheduler.WorkerState.CPU_ACQUIRED));
        assertTrue(onDefault.toString().contains("Dispatchers.Default")
                && onDefault.toString().contains("runBlocking"), onDefault.toString());
        BlockingOnNonBlockingThreadDetector.Report onIo = runBlockingOn(new CoroutineScheduler.Worker(
                () -> { }, CoroutineScheduler.WorkerState.BLOCKING));
        assertTrue(onIo.hasIssues(), "a worker running Dispatchers.IO work is inside a coroutine, where "
                + "runBlocking parks the worker and deadlocks a dispatcher with no thread to spare");
        assertTrue(onIo.toString().contains("coroutine") && onIo.toString().contains("runBlocking"),
                onIo.toString());
        assertEquals(1, onIo.structuredViolations.size());
    }

    @Test
    @DisplayName("one worker is one finding, whatever coroutine kotlinx's debug mode names in it (#1007)")
    void debugModeCoroutineNamesDoNotSplitAWorker() {
        BlockingOnNonBlockingThreadDetector detector = new BlockingOnNonBlockingThreadDetector();
        for (String coroutine : new String[] {"#2", "#9", "#19"}) {
            CoroutineScheduler.Worker worker = new CoroutineScheduler.Worker(() -> { },
                    CoroutineScheduler.WorkerState.CPU_ACQUIRED);
            worker.setName("DefaultDispatcher-worker-1 @coroutine" + coroutine);
            detector.recordRunBlocking(worker);
            detector.recordBlockingCall("Thread.sleep", worker);
        }
        BlockingOnNonBlockingThreadDetector.Report report = detector.analyze();
        assertEquals(2, report.violations.size(), report.toString());
        assertFalse(report.toString().contains("@coroutine"), report.toString());
    }

    @Test
    @DisplayName("a runBlocking finding says to suspend instead, not to move to Dispatchers.IO (#1007)")
    void runBlockingHasItsOwnRemedy() {
        String report = runBlockingOn(new CoroutineScheduler.Worker(() -> { },
                CoroutineScheduler.WorkerState.BLOCKING)).toString();
        assertTrue(report.contains("suspend"), report);
        assertFalse(report.contains("Dispatchers.IO"),
                "runBlocking on a Dispatchers.IO worker is reported too, so moving there is no fix: " + report);
    }

    @Test
    @DisplayName("runBlocking on an event loop is reported, on an ordinary thread it is not (#1007)")
    void runBlockingFollowsTheThread() {
        assertTrue(runBlockingOn(new ReactorThread(() -> { })).toString().contains("Reactor"));
        assertFalse(runBlockingOn(new FastThreadLocalThread(() -> { }, true)).hasIssues(),
                "a Netty thread that permits blocking may block in runBlocking like anywhere else");
        assertFalse(runBlockingOn(new Thread(() -> { })).hasIssues(),
                "runBlocking on an ordinary thread is its purpose: bridging blocking code to suspending code");
    }

    @Test
    @DisplayName("an ordinary thread that blocks is not reported")
    void ordinaryThreadIsNotReported() {
        assertFalse(blockOn(new Thread(() -> { })).hasIssues());
        assertFalse(new BlockingOnNonBlockingThreadDetector().analyze().hasIssues());
    }
}
