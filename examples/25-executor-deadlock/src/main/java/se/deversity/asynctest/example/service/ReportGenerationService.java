package se.deversity.asynctest.example.service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A report generation service that orchestrates data collection and report assembly.
 *
 * BUG: generateReport() runs inside a single-threaded executor. It submits a
 * subtask to the *same* executor to collect raw data, then calls Future.get()
 * on that subtask while still occupying the one available thread.
 *
 * The subtask is queued behind the currently running task and can never start.
 * The running task blocks forever waiting for a result that will never arrive.
 * This is a classic executor self-deadlock.
 *
 * FIX: Use a separate executor for the subtask, or restructure with
 * CompletableFuture.supplyAsync(...).thenApply(...) so that no thread
 * blocks waiting for a sibling task.
 */
public class ReportGenerationService {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "report-executor");
        t.setDaemon(true);
        return t;
    });

    /**
     * Generate a sales report by first collecting raw data via a subtask,
     * then assembling the final document.
     *
     * DEADLOCK: This method is submitted to {@code executor} by the caller.
     * It then submits {@code collectRawData()} to the same executor and
     * calls {@code get()} — but the single thread is occupied by *this* call,
     * so the data-collection subtask queues up and never executes.
     */
    public String generateReport(String reportId) throws ExecutionException, InterruptedException {
        // Submits a subtask to the SAME single-thread executor and then blocks on it.
        Future<String> dataFuture = executor.submit(() -> collectRawData(reportId));
        // This get() will block forever — the executor has no free thread to run dataFuture.
        String rawData = dataFuture.get();
        return assembleReport(reportId, rawData);
    }

    private String collectRawData(String reportId) {
        // Simulate lightweight database read
        return "sales_data_for_" + reportId;
    }

    private String assembleReport(String reportId, String data) {
        return "Report[" + reportId + "]: " + data.toUpperCase();
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
