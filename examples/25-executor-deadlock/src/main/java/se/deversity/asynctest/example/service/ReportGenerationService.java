package se.deversity.asynctest.example.service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A report generation service that orchestrates data collection and report assembly.
 *
 * <p>BUG: generateReport() runs inside a single-threaded executor. It submits a
 * subtask to the <em>same</em> executor to collect raw data, then waits on that
 * subtask while still occupying the one available thread.
 *
 * <p>The subtask is queued behind the currently running task and can never start.
 * The running task waits for a result that will never arrive. This is a classic
 * executor self-deadlock.
 *
 * <p>The wait is bounded so this example terminates. In the shape this is drawn from,
 * {@code dataFuture.get()} has no timeout and the thread is gone for the life of the
 * process; with a timeout the request fails instead, every time, which is the same bug
 * wearing a different symptom. {@link #SUBTASK_TIMEOUT_MS} is what makes the difference
 * between a demonstration and a hung build.
 *
 * <p>FIX: Use a separate executor for the subtask, or restructure with
 * CompletableFuture.supplyAsync(...).thenApply(...) so that no thread
 * blocks waiting for a sibling task.
 *
 * <p>INSTRUMENTATION: ExecutorDeadlockDetector is recording-fed. It needs to be told when a
 * task is submitted, when one starts, when one finishes, and when a running task begins
 * waiting on a sibling; that last one is the fact nothing else can supply. The four hooks
 * below are plain Runnables that default to no-ops, so the production path never touches the
 * test library. This is the seam, not the bug.
 */
public class ReportGenerationService {

    /** How long a parent task waits for the sibling it can never let run. */
    public static final long SUBTASK_TIMEOUT_MS = 100L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "report-executor");
        t.setDaemon(true);
        return t;
    });

    private volatile Runnable onTaskSubmitted = () -> { };

    private volatile Runnable onTaskStarted = () -> { };

    private volatile Runnable onTaskCompleted = () -> { };

    private volatile Runnable onWaitingOnSibling = () -> { };

    /**
     * Submits a report job the way production does: onto this service's own executor.
     *
     * <p>This is the step the sequential tests skip. Calling generateReport() straight from
     * the caller's thread leaves the executor thread idle, so the subtask runs and nothing
     * deadlocks - which is exactly why a single-threaded test of this class passes.
     *
     * @param reportId the report to generate
     * @return the pending report; it completes exceptionally once the subtask wait expires
     */
    public Future<String> submitReport(String reportId) {
        onTaskSubmitted.run();
        return executor.submit(() -> {
            onTaskStarted.run();
            try {
                return generateReport(reportId);
            } finally {
                onTaskCompleted.run();
            }
        });
    }

    /**
     * Generate a sales report by first collecting raw data via a subtask,
     * then assembling the final document.
     *
     * <p>DEADLOCK: when this method is running on {@code executor}, the submit below queues
     * behind it and the wait that follows can never be satisfied, because the one thread that
     * could satisfy it is the one doing the waiting.
     *
     * @param reportId the report to generate
     * @return the assembled report
     * @throws ExecutionException   if the subtask itself failed
     * @throws InterruptedException if the waiting thread was interrupted
     * @throws IllegalStateException when the subtask never got a thread, which is the bug
     */
    public String generateReport(String reportId) throws ExecutionException, InterruptedException {
        onTaskSubmitted.run();
        Future<String> dataFuture = executor.submit(() -> collectRawData(reportId));

        // The parent is now blocked on a task that shares its only thread.
        onWaitingOnSibling.run();
        try {
            String rawData = dataFuture.get(SUBTASK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return assembleReport(reportId, rawData);
        } catch (TimeoutException e) {
            dataFuture.cancel(false);
            throw new IllegalStateException(
                    "self-deadlock: collectRawData(" + reportId + ") is queued behind the task "
                            + "waiting for it, on a single-threaded executor", e);
        }
    }

    /**
     * Installs the hooks ExecutorDeadlockDetector needs. No-ops by default.
     *
     * @param onSubmitted        called as each task is handed to the executor
     * @param onStarted          called as a task begins running
     * @param onCompleted        called as a task finishes, however it finishes
     * @param onWaiting          called as a running task begins waiting on a sibling
     */
    public void observeExecutor(Runnable onSubmitted, Runnable onStarted,
                                Runnable onCompleted, Runnable onWaiting) {
        this.onTaskSubmitted = onSubmitted;
        this.onTaskStarted = onStarted;
        this.onTaskCompleted = onCompleted;
        this.onWaitingOnSibling = onWaiting;
    }

    private String collectRawData(String reportId) {
        // Simulate lightweight database read
        return "sales_data_for_" + reportId;
    }

    private String assembleReport(String reportId, String data) {
        return "Report[" + reportId + "]: " + data.toUpperCase();
    }

    /**
     * {@return the executor this service owns, which the detector tracks by identity}
     */
    public ExecutorService getExecutor() {
        return executor;
    }

    /** Stops the executor and drops anything still queued. */
    public void shutdown() {
        executor.shutdownNow();
    }
}
