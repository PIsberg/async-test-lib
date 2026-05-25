package se.deversity.asynctest.example.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * BUGGY service that demonstrates blocking inside ForkJoinPool.commonPool().
 *
 * BUG: generateReport() submits a task to the common pool via supplyAsync()
 *      (no executor argument). Inside that task it calls fetchData() which
 *      also submits to the common pool and then blocks with .get(). A common-
 *      pool thread is held blocked waiting for another common-pool thread —
 *      under concurrent load this exhausts pool parallelism and causes
 *      apparent deadlock.
 *
 * FIX: Pass a dedicated ExecutorService to every supplyAsync() call so that
 *      blocking waits occur on threads not owned by the common pool.
 */
public class ReportService {

    /**
     * Generates a report by fetching data asynchronously.
     * BUG: uses common pool for both outer and inner tasks.
     */
    public String generateReport(String reportId) {
        return CompletableFuture.supplyAsync(() -> {
            // BUG: fetchData also uses common pool; .get() blocks this thread
            String data = fetchData(reportId);
            return "Report[" + reportId + "]: " + data;
        }).join();
    }

    private String fetchData(String reportId) {
        try {
            // BUG: submits to common pool and immediately blocks on it
            return CompletableFuture.supplyAsync(() -> "data-for-" + reportId).get();
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            return "error";
        }
    }
}
