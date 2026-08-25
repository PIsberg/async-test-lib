package com.example.agentfixture;

/**
 * A static lazy-init whose published value keeps working after publication.
 *
 * <p>The loud half of the {@code PUTSTATIC} pair (#337), and the twin of
 * {@link StaticLazyCacheBean}: same field shape, same miss check, same unguarded store, same
 * access stream. Convergence is a property of the field, so nothing about the field can tell the
 * two apart. What differs is the payload. Here the loser's job is a live thing that keeps
 * writing its own state, so the class-scope miss check did the work twice and one of the two
 * results is being computed by an object nobody will ever read.
 *
 * <p>Kept as a separate class rather than a second field on the cache bean so each half owns its
 * own static state and neither test can leave the other one warm.
 */
public final class StaticLazySubmitBean {

    /**
     * The payload. Unlike a snapshot it is not finished when it is published: {@code advance}
     * writes its own field afterwards, which is the evidence that separates a side effect from
     * a value.
     */
    public static final class Job {
        private int progress;

        /** Moves this job's own state on, after it has already been published. */
        public void advance() {
            progress++;
        }

        /** {@return how far this job has got} */
        public int progress() {
            return progress;
        }
    }

    private static Job job;

    private StaticLazySubmitBean() {
    }

    /**
     * Reads the job and, on a miss, submits one.
     *
     * @param afterMiss run after the miss check and before the store, so a test can force both
     *                  threads to miss
     * @return the job this caller ended up with, which is not necessarily the one it created
     */
    public static Job submit(Runnable afterMiss) {
        Job local = job;
        if (local == null) {
            afterMiss.run();
            local = new Job();
            job = local;
        }
        return local;
    }

    /** {@return the submitted job without submitting one} */
    public static Job peek() {
        return job;
    }

    /** Drops the job so each test starts from a miss. */
    public static void reset() {
        job = null;
    }
}
