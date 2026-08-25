package se.deversity.asynctest.example.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;

/**
 * BUGGY service that demonstrates blocking inside ForkJoinPool.commonPool().
 *
 * <p>BUG: generateReport() submits a task to the common pool via supplyAsync()
 * with no executor argument. Inside that task it calls fetchData(), which also
 * submits to the common pool and then blocks on get(). A common-pool thread is
 * held blocked waiting for another common-pool thread, and under concurrent load
 * that exhausts the pool's parallelism. Every parallel stream in the JVM shares
 * that pool, so the damage is not confined to this class.
 *
 * <p>FIX: Pass a dedicated ExecutorService to every supplyAsync() call so that
 * blocking waits happen on threads the common pool does not own.
 *
 * <p>INSTRUMENTATION: CompletableFutureCommonPoolBlockingDetector is recording-fed and
 * deliberately narrow: recordBlockingCall ignores any future that was not first registered
 * through recordCommonPoolSubmission, so both calls have to happen, in that order, and from
 * inside the code that does the work. The two hooks below default to no-ops, so the
 * production path never touches the test library. This is the seam, not the bug.
 */
public class ReportService {

    private volatile BiConsumer<Object, String> onCommonPoolSubmission = (future, name) -> { };

    private volatile BiConsumer<Object, String> onBlockingCall = (future, callType) -> { };

    /**
     * Generates a report by fetching data asynchronously.
     *
     * <p>BUG: uses the common pool for both the outer and the inner task.
     *
     * @param reportId the report to generate
     * @return the assembled report line
     */
    public String generateReport(String reportId) {
        return CompletableFuture.supplyAsync(() -> {
            // BUG: fetchData also uses the common pool, and blocks this common-pool thread
            // waiting for it.
            String data = fetchData(reportId);
            return "Report[" + reportId + "]: " + data;
        }).join();
    }

    private String fetchData(String reportId) {
        // BUG: no executor argument, so this goes to the common pool too.
        CompletableFuture<String> inner =
                CompletableFuture.supplyAsync(() -> "data-for-" + reportId);
        onCommonPoolSubmission.accept(inner, "fetchData");
        try {
            // Recorded before the wait, on the thread that is about to be parked. The detector
            // ignores a blocking call on a future it has not been told about, so the order of
            // these two lines is part of the contract, not stylistic.
            onBlockingCall.accept(inner, "Future.get");
            return inner.get();      // BUG: blocks a common-pool thread on a common-pool task
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            return "error";
        }
    }

    /**
     * Installs the hooks CompletableFutureCommonPoolBlockingDetector needs. No-ops by default.
     *
     * @param onSubmission called with each future submitted to the common pool, and a label
     * @param onBlocking   called with the future about to be waited on, and the kind of wait
     */
    public void observeCommonPool(BiConsumer<Object, String> onSubmission,
                                  BiConsumer<Object, String> onBlocking) {
        this.onCommonPoolSubmission = onSubmission;
        this.onBlockingCall = onBlocking;
    }
}
