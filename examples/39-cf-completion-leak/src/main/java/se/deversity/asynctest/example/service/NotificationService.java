package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * BUGGY service that demonstrates CompletableFuture completion leak.
 *
 * BUG: sendNotification() creates a CompletableFuture per notification and
 *      adds it to a list so callers can await delivery. However, complete()
 *      is never called — any thread that calls pendingFutures.get(i).get()
 *      blocks forever, and the list grows without bound.
 *
 * FIX: call future.complete(null) after the notification is dispatched, or
 *      return CompletableFuture.completedFuture(null) when the result is
 *      available immediately.
 */
public class NotificationService {

    // BUG: futures are added here but complete() is never called on them
    private final List<CompletableFuture<Void>> pendingFutures = new ArrayList<>();

    /**
     * Sends a notification. Returns a future that should complete when the
     * notification is delivered — but the implementation forgets to complete it.
     */
    public CompletableFuture<Void> sendNotification(String message) {
        CompletableFuture<Void> delivery = new CompletableFuture<>();
        pendingFutures.add(delivery);   // BUG: stored but never completed
        // Dispatch logic (simulated)
        System.out.println("Sending: " + message);
        // BUG: delivery.complete(null) is missing here
        return delivery;
    }

    public int pendingCount() {
        return pendingFutures.size();
    }

    public List<CompletableFuture<Void>> getPendingFutures() {
        return pendingFutures;
    }
}
