package kotlinx.coroutines;

/**
 * Test stand-in for the facade class Kotlin compiles a {@code runBlocking} call against, by its real
 * name. The weaver matches the call site's owner and method name, never the descriptor, so the
 * Kotlin types the real one takes are not needed here: {@code block} runs on the caller, as the
 * real one runs its coroutine on the thread it blocks.
 */
public final class BuildersKt {

    private BuildersKt() {
    }

    /**
     * Runs {@code block} on the calling thread.
     *
     * @param context ignored
     * @param block   the work
     * @return null
     */
    public static Object runBlocking(Object context, Runnable block) {
        block.run();
        return null;
    }
}
