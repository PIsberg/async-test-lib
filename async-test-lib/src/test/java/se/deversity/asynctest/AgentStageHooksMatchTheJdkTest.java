package se.deversity.asynctest;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@link AgentConcurrencyUtilHooks} call the weaver substitutes for a {@code CompletionStage}
 * or {@code CompletableFuture} method returns what the JDK call it replaces returns (#970).
 *
 * <p>The hooks run only where the agent weaves user code, so the library module's tests did not
 * call most of them: the 2026-10-08 mutation run found 231 of their mutants with no coverage at
 * all. A hook that dropped the stage, returned the wrong one or took the wrong branch for a
 * stage that is not a {@code CompletableFuture} would change what the woven program computes.
 * This calls each hook with no run installed, on a {@code CompletableFuture} and, for the
 * {@code CompletionStage} overloads, also on a stage that is not one, and compares the outcome with
 * the same call made directly.
 */
class AgentStageHooksMatchTheJdkTest {

    private static final String VALUE = "v";
    private static final RuntimeException FAILURE = new IllegalStateException("boom");

    /** Counts what ran on it and runs it at once, so an executor overload is seen to use it. */
    private static final class CountingExecutor implements Executor {
        final AtomicInteger used = new AtomicInteger();

        @Override
        public void execute(Runnable command) {
            used.incrementAndGet();
            command.run();
        }
    }

    @Test
    @DisplayName("every stage hook returns what the JDK method it replaces returns")
    void everyStageHookMatchesTheJdk() throws Exception {
        List<String> mismatches = new ArrayList<>();
        int checked = 0;
        for (Method hook : AgentConcurrencyUtilHooks.class.getMethods()) {
            Method jdk = jdkTwin(hook);
            if (jdk == null) {
                continue;
            }
            boolean stageTyped = hook.getParameterTypes()[0] == CompletionStage.class;
            for (boolean foreign : stageTyped ? new boolean[] {false, true} : new boolean[] {false}) {
                checked++;
                String hooked = outcome(hook, foreign, true);
                String direct = outcome(jdk, false, false);
                if (!hooked.equals(direct)) {
                    mismatches.add(hook.getName() + Arrays.toString(hook.getParameterTypes())
                            + (foreign ? " on a foreign stage" : "") + ": hook " + hooked
                            + ", JDK " + direct);
                }
            }
        }
        assertTrue(checked >= 60, "checked only " + checked + " hook calls; the scan stopped"
                + " matching the hooks it exists for");
        assertTrue(mismatches.isEmpty(), "these woven calls compute something other than the call"
                + " they replace:\n  " + String.join("\n  ", mismatches));
    }

    /** {@return the CompletionStage method a hook stands in for, or null for a hook of another kind} */
    private static Method jdkTwin(Method hook) {
        Class<?>[] params = hook.getParameterTypes();
        if (!Modifier.isStatic(hook.getModifiers()) || params.length == 0
                || (params[0] != CompletionStage.class && params[0] != CompletableFuture.class)) {
            return null;
        }
        try {
            return CompletionStage.class.getMethod(hook.getName(), Arrays.copyOfRange(params, 1, params.length));
        } catch (NoSuchMethodException notAStageMethod) {
            return null;
        }
    }

    /**
     * Runs {@code method} on a fresh receiver and fresh arguments and {@return its outcome as text}.
     * The receiver fails for the {@code exceptionally} family, so the function has something to
     * recover, and completes with {@link #VALUE} otherwise.
     */
    private static String outcome(Method method, boolean foreign, boolean viaHook) throws Exception {
        boolean recovering = method.getName().startsWith("exceptionally");
        CompletableFuture<Object> source = recovering
                ? CompletableFuture.failedFuture(FAILURE) : CompletableFuture.completedFuture(VALUE);
        Object receiver = foreign ? foreignStage(source) : source;
        Class<?>[] types = method.getParameterTypes();
        int first = viaHook ? 1 : 0;
        Object[] args = new Object[types.length - first + (viaHook ? 1 : 0)];
        int at = 0;
        if (viaHook) {
            args[at++] = receiver;
        }
        CountingExecutor executor = new CountingExecutor();
        StringBuilder effects = new StringBuilder();
        for (int i = first; i < types.length; i++) {
            args[at++] = argument(types[i], method.getName(), executor, effects);
        }
        Object result;
        try {
            result = viaHook ? method.invoke(null, args) : method.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            return "threw " + e.getCause().getClass().getSimpleName();
        }
        String settled;
        try {
            settled = "value " + ((CompletionStage<?>) result).toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            settled = "failed " + e.getCause().getClass().getSimpleName();
        }
        return settled + " effects " + effects + (executor.used.get() > 0 ? " on the executor" : "");
    }

    /** One argument of the given type, recording what the stage did with it in {@code effects}. */
    @SuppressWarnings("unchecked")
    private static Object argument(Class<?> type, String name, Executor executor, StringBuilder effects) {
        if (type == Executor.class) {
            return executor;
        }
        if (type == CompletionStage.class) {
            return CompletableFuture.completedFuture("other");
        }
        if (type == Runnable.class) {
            return (Runnable) () -> effects.append("ran;");
        }
        if (type == Consumer.class) {
            return (Consumer<Object>) v -> effects.append("accepted ").append(v).append(';');
        }
        if (type == BiConsumer.class) {
            return (BiConsumer<Object, Object>) (a, b) -> effects.append("accepted ").append(a)
                    .append('+').append(b instanceof Throwable t ? t.getClass().getSimpleName() : b)
                    .append(';');
        }
        if (type == BiFunction.class) {
            return (BiFunction<Object, Object, Object>) (a, b) -> a + "+"
                    + (b instanceof Throwable t ? t.getClass().getSimpleName() : b);
        }
        if (type == Function.class) {
            if (name.contains("Compose")) {
                return (Function<Object, Object>) v -> CompletableFuture.completedFuture("composed " + label(v));
            }
            return (Function<Object, Object>) v -> "applied " + label(v);
        }
        throw new AssertionError("no argument for " + type + " in " + name);
    }

    private static String label(Object v) {
        return v instanceof Throwable t ? t.getClass().getSimpleName() : String.valueOf(v);
    }

    /** A CompletionStage that is not a CompletableFuture, delegating every call to {@code delegate}. */
    @SuppressWarnings("unchecked")
    private static CompletionStage<Object> foreignStage(CompletableFuture<Object> delegate) {
        InvocationHandler handler = (proxy, method, args) -> {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        CompletionStage<Object> stage = (CompletionStage<Object>) Proxy.newProxyInstance(
                CompletionStage.class.getClassLoader(), new Class<?>[] {CompletionStage.class}, handler);
        assertFalse(stage instanceof CompletableFuture, "the foreign stage must reach the other branch");
        return stage;
    }

    // ---- the hooks that stand in for static or Future, Executor, queue calls ----

    @Test
    @DisplayName("supplyAsync and runAsync run the task, on the given executor when there is one")
    void supplyAndRunAsync() throws Exception {
        assertEquals(VALUE, AgentConcurrencyUtilHooks.supplyAsync(() -> VALUE).get(5, TimeUnit.SECONDS));
        CountingExecutor executor = new CountingExecutor();
        assertEquals(VALUE, AgentConcurrencyUtilHooks.supplyAsync(() -> VALUE, executor).get(5, TimeUnit.SECONDS));
        AtomicInteger ran = new AtomicInteger();
        AgentConcurrencyUtilHooks.runAsync(ran::incrementAndGet).get(5, TimeUnit.SECONDS);
        AgentConcurrencyUtilHooks.runAsync(ran::incrementAndGet, executor).get(5, TimeUnit.SECONDS);
        assertEquals(2, ran.get());
        assertEquals(2, executor.used.get(), "both executor overloads ran on the executor");
        assertThrows(NullPointerException.class, () -> AgentConcurrencyUtilHooks.supplyAsync(null));
        assertThrows(NullPointerException.class, () -> AgentConcurrencyUtilHooks.runAsync(null));
    }

    @Test
    @DisplayName("join and get return the value, and get rethrows the failure as the JDK does")
    void joinAndGet() throws Exception {
        CompletableFuture<Object> done = CompletableFuture.completedFuture(VALUE);
        assertEquals(VALUE, AgentConcurrencyUtilHooks.join(done));
        assertEquals(VALUE, AgentConcurrencyUtilHooks.get(done));
        assertEquals(VALUE, AgentConcurrencyUtilHooks.get(done, 1, TimeUnit.SECONDS));
        CompletableFuture<Object> failed = CompletableFuture.failedFuture(FAILURE);
        assertThrows(CompletionException.class, () -> AgentConcurrencyUtilHooks.join(failed));
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> AgentConcurrencyUtilHooks.get(failed, 1, TimeUnit.SECONDS));
        assertSame(FAILURE, e.getCause());
    }

    @Test
    @DisplayName("completeExceptionally, obtrudeValue and obtrudeException change the future as the JDK does")
    void completionWrites() {
        CompletableFuture<Object> future = new CompletableFuture<>();
        assertTrue(AgentConcurrencyUtilHooks.completeExceptionally(future, FAILURE));
        assertTrue(future.isCompletedExceptionally());
        assertFalse(AgentConcurrencyUtilHooks.completeExceptionally(future, FAILURE),
                "a second completion reports false, as the JDK's does");
        AgentConcurrencyUtilHooks.obtrudeValue(future, VALUE);
        assertEquals(VALUE, future.join());
        AgentConcurrencyUtilHooks.obtrudeException(future, FAILURE);
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    @DisplayName("submit runs the task and returns its future, put enqueues")
    void submitAndPut() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicInteger ran = new AtomicInteger();
            AgentConcurrencyUtilHooks.submit(pool, (Runnable) ran::incrementAndGet).get(5, TimeUnit.SECONDS);
            assertEquals(VALUE, AgentConcurrencyUtilHooks.submit(pool, (Runnable) ran::incrementAndGet, VALUE)
                    .get(5, TimeUnit.SECONDS));
            assertEquals(VALUE, AgentConcurrencyUtilHooks.submit(pool, (Callable<Object>) () -> VALUE)
                    .get(5, TimeUnit.SECONDS));
            assertEquals(2, ran.get());
        } finally {
            pool.shutdownNow();
        }
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(1);
        AgentConcurrencyUtilHooks.put(queue, VALUE);
        assertEquals(VALUE, queue.poll());
    }
}
