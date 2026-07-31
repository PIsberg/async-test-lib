package se.deversity.asynctest.example.service;

import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hands work items out to a pool of workers.
 *
 * <p>The tempting shortcut is to build one {@link Iterator} over the work list and let every
 * worker pull from it — it looks like a queue and it is already there. It is not a queue. An
 * iterator is a <strong>cursor</strong>: {@code hasNext()} and {@code next()} are two
 * separate reads of mutable state, and nothing between them stops another thread from
 * consuming the element this one just confirmed.
 *
 * <p>Making the collection concurrent does not help. A {@link CopyOnWriteArrayList}'s
 * iterator is a snapshot, and a {@link ConcurrentLinkedQueue}'s is weakly consistent — both
 * are thread-safe to <em>create</em> from any thread, and neither is safe to <em>share</em>.
 * The concurrency guarantee is about the collection, not about the cursor into it. Shared
 * iteration skips items, hands the same item to two workers, or throws
 * {@link java.util.NoSuchElementException} from the {@code next()} that follows a
 * {@code hasNext()} which was true a microsecond ago.
 */
public final class WorkQueueService {

    private final List<String> work;

    /** BUG: one cursor over the work list, shared by every worker thread. */
    private final Iterator<String> sharedCursor;

    /** The fix: a real concurrent queue, whose poll() is one atomic operation. */
    private final Queue<String> safeQueue;

    public WorkQueueService(List<String> work) {
        this.work = List.copyOf(work);
        this.sharedCursor = this.work.iterator();
        this.safeQueue = new ConcurrentLinkedQueue<>(this.work);
    }

    /**
     * BUG: check-then-act across two calls on a shared cursor. Between {@code hasNext()}
     * returning true and {@code next()} running, another worker can take the last element —
     * and this call throws.
     */
    public String takeNext() {
        if (sharedCursor.hasNext()) {
            return sharedCursor.next();
        }
        return null;
    }

    /**
     * The fix: {@code poll()} is a single atomic operation that returns the element or
     * {@code null}. No check-then-act, no cursor to share.
     */
    public String takeNextSafely() {
        return safeQueue.poll();
    }

    /**
     * Also fine: a cursor created and consumed entirely within one thread. Iterators are
     * cheap — the confinement is the point, not the allocation.
     */
    public int countLocally() {
        int count = 0;
        for (Iterator<String> local = work.iterator(); local.hasNext(); local.next()) {
            count++;
        }
        return count;
    }

    public int remainingInSafeQueue() {
        return safeQueue.size();
    }
}
