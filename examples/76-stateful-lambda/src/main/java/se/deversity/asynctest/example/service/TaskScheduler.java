package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * BUGGY service that demonstrates shared mutable state captured in a lambda.
 *
 * BUG: scheduleCountingTasks() creates a single int[] array and captures it in
 *      a Runnable lambda. The same lambda instance (or different lambda instances
 *      capturing the same array) is submitted to n threads. Each thread does
 *      count[0]++ without synchronization — a classic lost-update race.
 *
 * FIX: use AtomicInteger for the captured counter, or create independent state
 *      per submitted task so each thread owns its own counter.
 */
public class TaskScheduler {

    /**
     * Schedule n counting tasks on the given pool.
     * BUG: all tasks share the same mutable int[] counter without synchronization.
     *
     * @param pool executor to submit tasks to
     * @param n    number of tasks to submit
     * @return list of futures (count[0] at the end is less than n due to races)
     */
    public List<Future<?>> scheduleCountingTasks(ExecutorService pool, int n) {
        // BUG: single mutable container captured by all n lambda instances.
        int[] count = {0};

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Future<?> f = pool.submit(() -> {
                // BUG: non-atomic read-modify-write on shared count[0].
                count[0]++;
            });
            futures.add(f);
        }
        return futures;
    }
}
