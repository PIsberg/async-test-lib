package se.deversity.asynctest;

import se.deversity.asynctest.diagnostics.ExplicitGcDetector;
import se.deversity.vibetags.annotations.AIContract;

/**
 * Hooks for {@code System.gc()}, the second user of the agent's static substitution path.
 *
 * <h2>Why this one needs no guard</h2>
 *
 * <p>{@link se.deversity.asynctest.AgentSleepHooks} records a sleep only while a lock is held,
 * because a sleep on its own is rate limiting, back-off or polling far more often than it is the
 * bug. An explicit collection has no such innocent twin. {@code System.gc()} is a request for a
 * full stop-the-world pause of indeterminate length, and inside a concurrent test that inflates
 * every latency measurement, invents timeouts and reschedules the threads whose interleaving the
 * run exists to explore. There is no held-lock question to ask first: the call is the finding.
 *
 * <h2>Why the call site is worth walking for</h2>
 *
 * <p>{@code ExplicitGcDetector} reports a thread and a location, and a static hook has no receiver
 * to name and no line number of its own. The stack walk supplies it. The cost that would normally
 * argue against walking a stack per call does not apply here, because the very next statement
 * requests a full GC: anything this hook does is free next to what it is reporting.
 */
@AIContract(reason = "Called from bytecode the agent rewrites, through the static substitution path: the method name and erased signature here are matched by CollectionAccessWeaver.GC_ENTRIES and cannot change independently of it. Record before collecting, not after: the finding is that the call was made, and a hook that recorded afterwards would lose the event if the collection never returned. The hook must perform the original System.gc() so that weaving does not change what the program does - a substitution that silently dropped the collection would alter behaviour the test may depend on, which is the one thing no weave may do. Unlike AgentSleepHooks there is deliberately no HeldLocks guard: an explicit collection is the bug whether or not a lock is held, and adding a guard would silence the common case. The stack walk must skip se.deversity.asynctest frames, or every finding would name this class instead of the caller.")
public final class AgentGcHooks {

    private AgentGcHooks() {
    }

    /**
     * Weaves {@code System.gc()}.
     *
     * <p>The suppression is the method rather than an exception to it. PMD is right that code
     * must not ask for a collection explicitly - reporting exactly that is why this hook exists -
     * but the substitution has already replaced the caller's own call, so this one has to happen.
     * A weave that silently dropped the collection would change what the woven program does,
     * which is the one thing no substitution here may do.
     */
    @SuppressWarnings("PMD.DoNotCallGarbageCollectionExplicitly")
    public static void gc() {
        ExplicitGcDetector detector = AsyncTestContext.currentExplicitGcDetector();
        if (detector != null) {
            detector.recordGcInvocation(Thread.currentThread(), callSite());
        }
        System.gc();
    }

    /**
     * {@return the first frame outside the library, as {@code Class.method:line}}
     *
     * <p>The frames this skips are the hook itself. Without the filter every finding would name
     * {@code AgentGcHooks.gc}, which is the one location the reader already knows.
     */
    private static String callSite() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(frame -> !frame.getClassName().startsWith("se.deversity.asynctest."))
                .findFirst()
                .map(frame -> frame.getClassName() + "." + frame.getMethodName()
                        + ":" + frame.getLineNumber())
                .orElse("unknown"));
    }
}
