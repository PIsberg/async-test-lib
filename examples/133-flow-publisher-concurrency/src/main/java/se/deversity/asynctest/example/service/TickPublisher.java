package se.deversity.asynctest.example.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Publishes price ticks to a {@link Flow.Subscriber}.
 *
 * <p>The Reactive Streams specification, which {@link java.util.concurrent.Flow} adopts
 * verbatim, has a rule that every subscriber is written against:
 *
 * <blockquote>Rule 1.3: {@code onSubscribe}, {@code onNext}, {@code onError} and
 * {@code onComplete} signalled to a Subscriber MUST be signalled serially.</blockquote>
 *
 * <p>Serial does not mean "from one thread". It means no two signals overlap in time. A
 * publisher may hop threads between signals as much as it likes, provided each one finishes
 * before the next begins. That guarantee is what lets a subscriber keep unsynchronised state —
 * a running total, a buffer, a parser position — without a lock, and essentially every
 * subscriber does.
 *
 * <p>The bug here is a publisher that fans out to a pool and calls {@code onNext} from several
 * threads at once. The subscriber's unsynchronised state is now shared mutable state and it
 * corrupts, but the stack trace points into the subscriber rather than the publisher that
 * broke the contract.
 *
 * <p>Two more violations of the same rule are worth knowing, and this class can produce both:
 * signalling after a terminal event ({@code onNext} following {@code onComplete}, which the
 * subscriber has every right to ignore or to fail on), and emitting more items than were
 * requested, which is rule 1.1 and overflows a bounded subscriber.
 *
 * <p>The fix is not to make subscribers thread-safe. It is to serialise in the publisher —
 * a lock, a drain loop, or simply doing the work on one thread — because the contract belongs
 * to the publisher and every downstream operator relies on it.
 */
public final class TickPublisher {

    private final ReentrantLock deliveryLock = new ReentrantLock();
    private final AtomicLong demand = new AtomicLong();

    /** BUG: fans out to the pool, so several onNext calls overlap. */
    public void publishConcurrently(ExecutorService pool, Flow.Subscriber<Long> subscriber, int ticks) {
        for (int i = 0; i < ticks; i++) {
            long price = 100L + i;
            pool.submit(() -> subscriber.onNext(price));
        }
    }

    /** The fix: one lock around delivery, so signals never overlap even across threads. */
    public void publishSerially(ExecutorService pool, Flow.Subscriber<Long> subscriber, int ticks) {
        for (int i = 0; i < ticks; i++) {
            long price = 100L + i;
            pool.submit(() -> {
                deliveryLock.lock();
                try {
                    subscriber.onNext(price);
                } finally {
                    deliveryLock.unlock();
                }
            });
        }
    }

    public void request(long n) {
        demand.addAndGet(n);
    }

    public long demand() {
        return demand.get();
    }

    /** A subscriber that keeps a running total without a lock — as rule 1.3 entitles it to. */
    public static final class TotallingSubscriber implements Flow.Subscriber<Long> {
        private long total;
        private int received;
        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(Long item) {
            total += item;      // safe only because signals are serial
            received++;
        }

        @Override
        public void onError(Throwable throwable) {
        }

        @Override
        public void onComplete() {
        }

        public long total() {
            return total;
        }

        public int received() {
            return received;
        }

        public Flow.Subscription subscription() {
            return subscription;
        }
    }
}
