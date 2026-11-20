package se.deversity.asynctest.agent;

import com.example.agentfixture.ThreadConstructionSample;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The construction marker (#737): every shape a thread is constructed in still verifies once
 * woven, and each thread it constructs is handed to the hook exactly once.
 *
 * <p>Loading a woven class verifies it, so a {@code DUP} inserted where the stack top was not the
 * new thread fails here with a {@code VerifyError} rather than inside somebody's test run.
 */
class ThreadConstructionWeaverTest {

    /** The threads the woven code reported constructing, in order. */
    public static final List<Thread> CONSTRUCTED = new CopyOnWriteArrayList<>();

    /** The targets the woven code handed over before constructing a thread, in order (#1350). */
    public static final List<Object> TARGETS = new CopyOnWriteArrayList<>();

    /** Stands in for the library hook, recording instead of marking. */
    public static class Recorder {

        /**
         * @param thread the thread the woven code just constructed
         */
        public static void threadConstructed(Thread thread) {
            CONSTRUCTED.add(thread);
        }

        /**
         * @param target the Runnable the woven code is about to construct a thread around
         */
        public static void threadTarget(Object target) {
            TARGETS.add(target);
        }
    }

    /** A library from before #1350: it marks constructions and takes no targets. */
    public static class MarkerOnly {

        /**
         * @param thread the thread the woven code just constructed
         */
        public static void threadConstructed(Thread thread) {
            CONSTRUCTED.add(thread);
        }
    }

    @BeforeEach
    void clear() {
        CONSTRUCTED.clear();
        TARGETS.clear();
    }

    private static Class<?> woven(Class<?> type) {
        return wovenWith(type, Recorder.class);
    }

    private static Class<?> wovenWith(Class<?> type, Class<?> hooks) {
        return new ByteBuddy()
                .redefine(type)
                .visit(ThreadConstructionWeaver.of(hooks))
                .make()
                .load(ThreadConstructionWeaverTest.class.getClassLoader(),
                        ClassLoadingStrategy.Default.CHILD_FIRST)
                .getLoaded();
    }

    @Test
    @DisplayName("every new Thread expression is marked once, nested, dropped or as an argument")
    void everyNewThreadIsMarkedOnce() throws Exception {
        Class<?> sample = woven(ThreadConstructionSample.class);
        Runnable task = () -> { };

        Thread plain = (Thread) sample.getMethod("plain", Runnable.class).invoke(null, task);
        assertEquals(List.of(plain), CONSTRUCTED, "a plain new Thread");

        CONSTRUCTED.clear();
        sample.getMethod("discarded", Runnable.class).invoke(null, task);
        assertEquals(1, CONSTRUCTED.size(), "a dropped thread is still constructed");

        CONSTRUCTED.clear();
        Thread outer = (Thread) sample.getMethod("nested", Runnable.class).invoke(null, task);
        assertEquals(2, CONSTRUCTED.size(), "both nested threads, the inner one first");
        assertSame(outer, CONSTRUCTED.get(1), "the outer thread completes last");

        CONSTRUCTED.clear();
        Collection<?> passedOn =
                (Collection<?>) sample.getMethod("asAnArgument", Runnable.class).invoke(null, task);
        assertEquals(List.copyOf(passedOn), CONSTRUCTED, "a thread passed straight on");

        CONSTRUCTED.clear();
        Thread branched = (Thread) sample.getMethod("withABranchInItsArguments",
                Runnable.class, boolean.class).invoke(null, task, true);
        assertEquals(List.of(branched), CONSTRUCTED, "a branch inside the arguments");
    }

    @Test
    @DisplayName("a subclass marks this after its superclass call, and a new Thread in that call")
    void aSubclassMarksItselfAfterItsSuperclassCall() throws Exception {
        Class<?> wrapping = woven(ThreadConstructionSample.WrappingThread.class);
        Runnable task = () -> { };

        Thread thread = (Thread) wrapping.getConstructor(Runnable.class).newInstance(task);
        assertEquals(2, CONSTRUCTED.size(),
                "the thread built for the superclass call, then the subclass instance");
        assertSame(thread, CONSTRUCTED.get(1), "this, once the superclass call initialised it");

        CONSTRUCTED.clear();
        Thread delegated = (Thread) wrapping.getConstructor().newInstance();
        assertEquals(2, CONSTRUCTED.size(),
                "a this(...) delegation marks nothing itself; the constructor it calls does");
        assertSame(delegated, CONSTRUCTED.get(1), "and it marks the same instance");
    }

    @Test
    @DisplayName("a deeper subclass is found through the pool and skips a new of its superclass")
    void aDeeperSubclassIsMarked() throws Exception {
        Class<?> deep = woven(ThreadConstructionSample.DeepThread.class);
        Runnable task = () -> { };

        Thread thread = (Thread) deep.getConstructor(Runnable.class).newInstance(task);

        assertEquals(List.of(thread), CONSTRUCTED,
                "only the subclass instance: the superclass instance built for its argument is "
                        + "marked by the superclass's own constructor, which is not woven here");
    }

    /**
     * #1350: a FutureTask run by {@code new Thread(task)} is run inside the JDK, so a get on it can
     * only be ordered if the library learns which thread took it, and the target is only on the
     * stack before the constructor call.
     */
    @Test
    @DisplayName("the Runnable of a new Thread(task) or new Thread(task, name) is handed over before the constructor")
    void theTargetOfANewThreadIsHandedOver() throws Exception {
        Class<?> sample = woven(ThreadConstructionSample.class);
        Runnable task = () -> { };

        sample.getMethod("plain", Runnable.class).invoke(null, task);
        assertEquals(List.of(task), TARGETS, "new Thread(task)");

        TARGETS.clear();
        sample.getMethod("asAnArgument", Runnable.class).invoke(null, task);
        assertEquals(List.of(task), TARGETS, "new Thread(task, name)");

        TARGETS.clear();
        CONSTRUCTED.clear();
        sample.getMethod("nested", Runnable.class).invoke(null, task);
        assertEquals(2, TARGETS.size(), "both nested constructions: " + TARGETS);
        assertSame(task, TARGETS.get(0), "the inner thread's task first");
        assertSame(CONSTRUCTED.get(0), TARGETS.get(1), "then the inner thread, the outer one's target");

        TARGETS.clear();
        sample.getMethod("withABranchInItsArguments", Runnable.class, boolean.class).invoke(null, task, true);
        assertEquals(List.of(task), TARGETS, "a branch inside the arguments");
    }

    @Test
    @DisplayName("the Runnable of a new Thread(group, task) or new Thread(group, task, name) is handed over too (#1401)")
    void theTargetOfAThreadInAGroupIsHandedOver() throws Exception {
        Class<?> sample = woven(ThreadConstructionSample.class);
        Runnable task = () -> { };

        Thread grouped = (Thread) sample.getMethod("inAGroup", Runnable.class).invoke(null, task);
        assertEquals(List.of(task), TARGETS, "new Thread(group, task)");
        assertEquals(List.of(grouped), CONSTRUCTED, "and the thread is marked as before");

        TARGETS.clear();
        Thread named = (Thread) sample.getMethod("inAGroupNamed", Runnable.class).invoke(null, task);
        assertEquals(List.of(task), TARGETS, "new Thread(group, task, name)");
        assertEquals("grouped", named.getName(), "the constructor still got its name");
    }

    @Test
    @DisplayName("a library without threadTarget still has its constructions marked")
    void aLibraryWithoutThreadTargetStillMarks() throws Exception {
        Class<?> sample = wovenWith(ThreadConstructionSample.class, MarkerOnly.class);
        Runnable task = () -> { };

        Thread plain = (Thread) sample.getMethod("plain", Runnable.class).invoke(null, task);

        assertEquals(List.of(plain), CONSTRUCTED, "the marker is unchanged");
        assertEquals(List.of(), TARGETS, "and no call is woven to a hook that is not there");
    }

    @Test
    @DisplayName("a hooks class without threadConstructed is refused when the table is built")
    void aMissingHookIsRefused() {
        assertThrows(IllegalStateException.class, () -> ThreadConstructionWeaver.of(Object.class),
                "a library without the hook is a version skew, and weaving a call to a method "
                        + "that is not there would fail inside user code");
    }
}
