package se.deversity.asynctest.agent;

import org.jspecify.annotations.Nullable;

import java.lang.instrument.Instrumentation;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Weaves a caller again when the class holding its wait helper turns up afterwards (#715).
 *
 * <h2>The ordering this exists for</h2>
 *
 * <p>{@code CollectionAccessWeaver} records a class's waiting methods when that class is woven and
 * reads them when a caller is woven, so a loop around a cross-class wait is marked only if the
 * helper's class went through the weaver first. Load-time weaving delivers the other order almost
 * every time: a class named only inside a method body is resolved lazily, on first execution of
 * the instruction naming it, so when the caller is woven at load its helper has not been loaded at
 * all. Measured on one pair of fixtures in one JVM, caller first gave 0 marks where helpers first
 * gave 1.
 *
 * <p>The failure is silent and on the reporting side. An unmarked loop makes its wait read as
 * {@code if (!ready) wait()}, and a correct bounded poll is reported as {@code MISSED_SIGNAL}.
 *
 * <h2>What it does</h2>
 *
 * <p>Every call the weaver could not resolve to a waiting method is remembered against the
 * signature it named. When some later class registers a waiting method with that signature, the
 * callers remembered against it are handed to a {@link Retransformer}, which in a live JVM is
 * {@code Instrumentation.retransformClasses}. Retransformation runs the whole transformer chain
 * again from the class's original bytes, and by then the index holds the helper, so the caller
 * comes out marked.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It does not close the window instantly. The retransformation is handed to a daemon thread
 * rather than run inside the transform that triggered it: a transform runs while a class is being
 * defined, and calling back into {@code retransformClasses} from there re-enters the transformer
 * chain under whatever locks the defining thread holds. A caller that executes its loop in the
 * few hundred microseconds between its helper's class loading and the retransformation landing
 * still runs the unmarked code, and a finding recorded in that window is still a finding. That is
 * one of the readings keeping {@code MISSED_SIGNAL} at {@code PROMPT}.
 *
 * <p>It is also bounded in the only direction that matters for a test suite's runtime: a caller is
 * handed over at most once per signature it could not resolve, so a class cannot be retransformed
 * repeatedly by the same helper turning up in several loaders.
 */
final class StaleCallerRetransformer {

    /** What to do with the callers that need weaving again. */
    @FunctionalInterface
    interface Retransformer {

        /**
         * Re-weaves the named classes.
         *
         * @param internalNames the classes to weave again, in internal form
         */
        void retransform(Set<String> internalNames);
    }

    /** Caller internal names, keyed by the {@code name + descriptor} they could not resolve. */
    private static final Map<String, Set<String>> CALLERS_BY_SIGNATURE = new ConcurrentHashMap<>();

    /** {@code caller + "<-" + signature} pairs already handed over, so each is handed over once. */
    private static final Set<String> HANDED_OVER = ConcurrentHashMap.newKeySet();

    /** Batches waiting for the daemon thread; unbounded because each entry is a few strings. */
    private static final BlockingQueue<Set<String>> PENDING = new LinkedBlockingQueue<>();

    private static final AtomicBoolean DRAINER_STARTED = new AtomicBoolean();

    /** Set by the test that owns the seam; null means the {@link Instrumentation} path. */
    private static volatile @Nullable Retransformer retransformer;

    private static volatile @Nullable Instrumentation instrumentation;

    private StaleCallerRetransformer() {
    }

    /**
     * Replaces what happens to a stale caller, for the test that owns this seam.
     *
     * @param action what to do with the callers, or {@code null} to restore the JVM path
     */
    static void useRetransformer(@Nullable Retransformer action) {
        retransformer = action;
    }

    /**
     * Records the handle the JVM path needs. Called once from the agent's install.
     *
     * @param inst the handle {@code premain} or {@code agentmain} was given
     */
    static void useInstrumentation(Instrumentation inst) {
        instrumentation = inst;
    }

    /**
     * Remembers that a woven class called something that might turn out to wait.
     *
     * @param callerInternalName the class being woven
     * @param signature the callee's {@code name + descriptor}
     */
    static void recordUnresolvedCall(String callerInternalName, String signature) {
        CALLERS_BY_SIGNATURE
                .computeIfAbsent(signature, key -> ConcurrentHashMap.newKeySet())
                .add(callerInternalName);
    }

    /**
     * Hands over the callers that could not resolve a signature now known to wait.
     *
     * <p>Called from inside a transform, so it must not throw and must not do the retransformation
     * itself. The class that declares the method is skipped: it has just been woven with the
     * knowledge, and weaving it again would only find the same answer.
     *
     * @param signature the {@code name + descriptor} that has just been registered as waiting
     * @param declaringInternalName the class that declares it
     */
    static void signatureBecameWaiting(String signature, String declaringInternalName) {
        Set<String> callers = CALLERS_BY_SIGNATURE.get(signature);
        if (callers == null) {
            return;
        }
        Set<String> stale = new LinkedHashSet<>();
        for (String caller : callers) {
            if (!caller.equals(declaringInternalName) && HANDED_OVER.add(caller + "<-" + signature)) {
                stale.add(caller);
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        Retransformer action = retransformer;
        if (action != null) {
            action.retransform(stale);
            return;
        }
        PENDING.add(stale);
        startDrainer();
    }

    /**
     * Starts the one daemon thread that performs the retransformations, if it is not running.
     *
     * <p>A daemon, so a JVM that is otherwise finished never waits for it, and one thread, so the
     * retransformations of a whole run are serialised rather than racing each other through
     * {@code Instrumentation}.
     */
    private static void startDrainer() {
        if (instrumentation == null || !DRAINER_STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread drainer = new Thread(StaleCallerRetransformer::drain, "asynctest-agent-reweave");
        drainer.setDaemon(true);
        drainer.start();
    }

    /** Takes batches off the queue for as long as the JVM lives, weaving each one again. */
    private static void drain() {
        while (true) {
            Set<String> batch;
            try {
                batch = PENDING.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            retransformNamed(batch);
        }
    }

    /**
     * Finds the loaded classes with these internal names and retransforms the modifiable ones.
     *
     * <p>Looked up by walking the loaded classes rather than by holding a reference from weave
     * time, because at weave time the class does not exist yet: the transformer sees its bytes
     * before it is defined. The walk is why batches are kept whole.
     *
     * <p>Everything here is swallowed. A class that cannot be retransformed costs its own mark and
     * nothing else, and this thread has no caller to report to.
     *
     * @param internalNames the classes to weave again
     */
    private static void retransformNamed(Set<String> internalNames) {
        Instrumentation inst = instrumentation;
        if (inst == null) {
            return;
        }
        try {
            Set<Class<?>> found = new LinkedHashSet<>();
            for (Class<?> loaded : inst.getAllLoadedClasses()) {
                if (internalNames.contains(loaded.getName().replace('.', '/'))
                        && inst.isModifiableClass(loaded)) {
                    found.add(loaded);
                }
            }
            if (!found.isEmpty()) {
                inst.retransformClasses(found.toArray(new Class<?>[0]));
            }
        } catch (Throwable whateverWentWrong) { // NOPMD - broad by design: see the javadoc
            // A failed re-weave leaves the caller as it was, which is the behaviour before #715.
        }
    }
}
