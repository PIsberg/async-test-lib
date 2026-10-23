package kotlinx.coroutines;

/**
 * Stand-in for kotlinx's {@code DispatchedTask}, the frame every dispatched coroutine body runs
 * under, so a test can put a {@code kotlinx.coroutines} frame on its own stack without the library
 * (#1007). {@code BlockingOnNonBlockingThreadDetector} recognises a coroutine on an ordinary thread
 * by exactly that frame.
 */
public final class DispatchedTask {

    private DispatchedTask() {
    }

    /** Runs {@code body} with this class's frame below it. */
    public static void run(Runnable body) {
        body.run();
    }
}
