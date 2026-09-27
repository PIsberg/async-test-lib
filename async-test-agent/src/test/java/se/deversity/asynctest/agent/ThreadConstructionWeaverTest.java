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

    /** Stands in for the library hook, recording instead of marking. */
    public static class Recorder {

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
    }

    private static Class<?> woven(Class<?> type) {
        return new ByteBuddy()
                .redefine(type)
                .visit(ThreadConstructionWeaver.of(Recorder.class))
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

    @Test
    @DisplayName("a hooks class without threadConstructed is refused when the table is built")
    void aMissingHookIsRefused() {
        assertThrows(IllegalStateException.class, () -> ThreadConstructionWeaver.of(Object.class),
                "a library without the hook is a version skew, and weaving a call to a method "
                        + "that is not there would fail inside user code");
    }
}
