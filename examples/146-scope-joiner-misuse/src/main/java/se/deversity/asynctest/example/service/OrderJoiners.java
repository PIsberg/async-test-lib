package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Two shapes of the same StructuredTaskScope.Joiner, written against plain collections so the
 * example compiles on JDK 21 while modelling the JEP 525 contract exactly.
 *
 * <p>What matters is which thread calls what. {@code onComplete} is invoked by the thread that
 * finished the subtask, so peers call it concurrently on one joiner instance. {@code onTimeout},
 * new in JDK 26, is invoked on the owner - and it is meant to return a partial result, which is
 * precisely the read that used to never happen.
 */
public final class OrderJoiners {

    private OrderJoiners() { }

    /** The buggy joiner: a plain ArrayList written from every subtask thread. */
    public static final class Collecting {
        private final List<String> done = new ArrayList<>();

        /** Called on the subtask's thread. No lock, no concurrent collection. */
        public void onComplete(String result) {
            done.add(result);
        }

        /** Called on the owner thread, while the subtasks above may still be running. */
        public List<String> onTimeout() {
            return List.copyOf(done);
        }

        /** {@return how many results were accumulated} */
        public int size() {
            return done.size();
        }
    }

    /** The fixed joiner: a concurrent queue, so onComplete needs no external coordination. */
    public static final class ConcurrentCollecting {
        private final Queue<String> done = new ConcurrentLinkedQueue<>();

        /** Called on the subtask's thread; the queue is safe for that. */
        public void onComplete(String result) {
            done.add(result);
        }

        /** Called on the owner thread; a snapshot of a concurrent queue is well defined. */
        public List<String> onTimeout() {
            return List.copyOf(done);
        }

        /** {@return how many results were accumulated} */
        public int size() {
            return done.size();
        }
    }
}
