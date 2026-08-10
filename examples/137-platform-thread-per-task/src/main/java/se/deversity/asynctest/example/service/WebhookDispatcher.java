package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Dispatches webhook payloads to subscribers, one delivery per thread.
 *
 * <p>The buggy shape is the oldest concurrency pattern in Java:
 *
 * <pre>{@code
 * for (String payload : payloads) {
 *     new Thread(() -> deliver(payload)).start();   // BUG: one OS thread per webhook
 * }
 * }</pre>
 *
 * <p>Each of those is a platform thread: an OS thread reservation, roughly 1 MB of stack, and
 * kernel scheduler load, with a hard system-wide ceiling. Ten webhooks in a unit test is
 * nothing; ten thousand an hour in production is an OOM or a pthread_create failure, and the
 * delivery burst that triggers it is exactly the moment the system is busiest.
 *
 * <p>The fix keeps thread-per-task — the model is right, the thread kind is wrong:
 *
 * <pre>{@code
 * for (String payload : payloads) {
 *     Thread.startVirtualThread(() -> deliver(payload));   // FIX
 * }
 * }</pre>
 *
 * <p>A virtual thread costs a few hundred bytes and no OS resources while blocked. Delivery is
 * I/O-bound waiting on subscriber endpoints, which is precisely the workload JEP 444 built
 * virtual threads for. (If the work were CPU-bound instead, a pool sized to the cores would be
 * the right tool.)
 *
 * <p>Both dispatch methods report each created thread to an observer so the example test can
 * hand it to the detector — production code would not carry that parameter.
 */
public final class WebhookDispatcher {

    /** BUG: one platform thread per payload. Returns the threads so a caller can join them. */
    public List<Thread> dispatchOnPlatformThreads(List<String> payloads, Consumer<Thread> observer) {
        List<Thread> threads = new ArrayList<>();
        for (String payload : payloads) {
            Thread worker = new Thread(() -> deliver(payload), "webhook-" + threads.size());
            observer.accept(worker);
            worker.start();
            threads.add(worker);
        }
        return threads;
    }

    /** FIX: one virtual thread per payload — same model, negligible cost. */
    public List<Thread> dispatchOnVirtualThreads(List<String> payloads, Consumer<Thread> observer) {
        List<Thread> threads = new ArrayList<>();
        for (String payload : payloads) {
            Thread worker = Thread.ofVirtual().name("webhook-vt-" + threads.size())
                    .unstarted(() -> deliver(payload));
            observer.accept(worker);
            worker.start();
            threads.add(worker);
        }
        return threads;
    }

    private void deliver(String payload) {
        // Stands in for the HTTP POST to the subscriber endpoint.
        payload.hashCode();
    }
}
