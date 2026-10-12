package kotlinx.coroutines.scheduling;

/**
 * Test stand-in for kotlinx.coroutines' scheduler, by its real names: a {@code Worker} thread whose
 * public {@code state} says whether it holds a CPU permit ({@code Dispatchers.Default}) or runs a
 * blocking task ({@code Dispatchers.IO}).
 */
public final class CoroutineScheduler {

    private CoroutineScheduler() {
    }

    /** The scheduler's worker states, as kotlinx.coroutines names them. */
    public enum WorkerState { CPU_ACQUIRED, BLOCKING, PARKING, DORMANT, TERMINATED }

    /** A worker thread. */
    public static final class Worker extends Thread {
        public WorkerState state;

        public Worker(Runnable task, WorkerState state) {
            super(task);
            this.state = state;
        }
    }
}
