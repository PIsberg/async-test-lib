package se.deversity.asynctest.diagnostics;

/**
 * The runner's worker slot for the calling thread: the worker's index within its round, {@code 0}
 * to {@code threads - 1}.
 *
 * <p>It is the only identity a worker has that outlives its thread. With
 * {@code useVirtualThreads = true} every body execution runs on a fresh virtual thread, so a
 * detector that asks "has this party been here before" across rounds has no thread to ask about
 * (#693). The slot comes back every round.
 *
 * <p>Kept here rather than on {@code AsyncTestContext} for the reason {@link HeldLocks} is: a
 * detector reads it, and diagnostics do not reach up into the context. The context sets it in
 * {@code install} and clears it in {@code uninstall}, so it falls under the same ThreadLocal
 * symmetry rule and cannot outlive the body execution that was given it.
 *
 * @since 1.12.2
 */
public final class WorkerSlot {

    /** What {@link #current()} returns on a thread the runner gave no slot. */
    public static final int NONE = -1;

    private static final ThreadLocal<Integer> SLOT = new ThreadLocal<>();

    private WorkerSlot() {}

    /**
     * Binds {@code slot} to the calling thread. Called from {@code AsyncTestContext.install}.
     *
     * @param slot the worker's index within its round, not negative
     */
    public static void set(int slot) {
        SLOT.set(slot);
    }

    /** {@return the calling thread's slot, or {@link #NONE}} */
    public static int current() {
        Integer slot = SLOT.get();
        return slot == null ? NONE : slot;
    }

    /** Unbinds the calling thread's slot. Called from {@code AsyncTestContext.uninstall()}. */
    public static void clear() {
        SLOT.remove();
    }
}
