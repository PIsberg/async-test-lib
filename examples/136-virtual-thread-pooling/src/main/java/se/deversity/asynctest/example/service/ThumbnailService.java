package se.deversity.asynctest.example.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Renders image thumbnails on background threads.
 *
 * <p>The service "migrated to virtual threads" the way many codebases do: somebody swapped the
 * thread factory in the existing pool wiring and called it done.
 *
 * <pre>{@code
 * // BUG: a pool of virtual threads
 * ExecutorService executor =
 *     Executors.newFixedThreadPool(4, Thread.ofVirtual().factory());
 * }</pre>
 *
 * <p>That line silently keeps everything JEP 444 removes. Concurrency is still capped at 4 —
 * submit a fifth render and it queues behind the others even though a virtual thread costs
 * almost nothing to create. The four pooled "workers" are virtual threads that never
 * terminate, so anything a render leaves in a {@code ThreadLocal} greets the next render on
 * that worker. And the pool still owns a queue, so slow renders still head-of-line block fast
 * ones.
 *
 * <p>The fix is one line, and it is not a smaller pool:
 *
 * <pre>{@code
 * // FIX: one fresh virtual thread per render
 * ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
 * }</pre>
 *
 * <p>If downstream capacity needs protecting, acquire a {@code Semaphore} around the guarded
 * call. A semaphore limits the operation; a pool limits the whole thread, which is exactly the
 * resource virtual threads made free.
 */
public final class ThumbnailService implements AutoCloseable {

    private final ExecutorService executor;

    private ThumbnailService(ExecutorService executor) {
        this.executor = executor;
    }

    /** BUG: virtual threads behind a fixed pool — capped, recycled, queued. */
    public static ThumbnailService pooledVirtual() {
        return new ThumbnailService(Executors.newFixedThreadPool(4, Thread.ofVirtual().factory()));
    }

    /** FIX: one fresh virtual thread per render. */
    public static ThumbnailService perTaskVirtual() {
        return new ThumbnailService(Executors.newVirtualThreadPerTaskExecutor());
    }

    /** The executor, exposed so a test can register it with the detector. */
    public ExecutorService executor() {
        return executor;
    }

    /** Submits one render. */
    public Future<byte[]> render(byte[] source) {
        return executor.submit(() -> scale(source));
    }

    private byte[] scale(byte[] source) {
        byte[] thumbnail = new byte[Math.max(1, source.length / 4)];
        for (int i = 0; i < thumbnail.length; i++) {
            thumbnail[i] = source[i * 4 % source.length];
        }
        return thumbnail;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
