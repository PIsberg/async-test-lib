package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether two different threads touched one subject inside a single invocation round.
 *
 * <p><strong>Why not the run's set of thread ids.</strong> The runner joins every worker before the
 * next round starts, so everything a round does happens-before everything the next one does. And
 * with virtual threads, the default, every body execution runs on a fresh thread with a fresh id.
 * A set of thread ids collected over the whole run therefore passes one on any subject a test
 * touches in two rounds, even with {@code threads = 1}, and a finding that means "used by more
 * than one thread at once" fires on a subject no two threads ever held at the same time. Two
 * threads inside one round is the evidence such a finding needs.
 *
 * <p>The round is the owning detector's own counter, bumped from its {@code markInvocationStart}.
 * A detector used standalone, with no round marks, keeps every access in round 0, which is the
 * whole-run behaviour it had before.
 *
 * <p>Lock-free and allocation-light: the first access of a round allocates one mark, and every
 * later access reads it; once sharing is seen the flag is sticky and the record path is one read.
 */
final class RoundSharing {

    /** The round being watched and the first thread seen in it. */
    private record Mark(long round, long threadId) { }

    private final AtomicReference<@Nullable Mark> mark = new AtomicReference<>();
    private volatile boolean shared;

    /**
     * Notes that {@code threadId} touched the subject in {@code round}.
     *
     * @param round    the owning detector's current invocation round
     * @param threadId the touching thread's id
     */
    void record(long round, long threadId) {
        while (!shared) {
            Mark seen = mark.get();
            if (seen != null && seen.round() == round) {
                if (seen.threadId() != threadId) {
                    shared = true;
                }
                return;
            }
            if (mark.compareAndSet(seen, new Mark(round, threadId))) {
                return;
            }
        }
    }

    /** {@return whether some single round saw two different threads touch the subject} */
    boolean sharedWithinARound() {
        return shared;
    }
}
