package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Analysis must survive a worker that is still recording (#1150).
 *
 * <p>When a worker outlives the quiesce grace, {@code ConcurrencyRunner} analyses anyway, so every
 * detector's {@code analyze()} can run while that worker is still inside a {@code record} method.
 * {@code DetectorRegistry.ifIssue} contains a throw, but a contained throw drops that detector's
 * whole report, so a detector whose analysis iterates a plain collection would lose its findings
 * exactly on the runs that hung. This drives every built detector the same way: two threads call
 * its public recording methods in a loop with arguments made from their parameter types, while a
 * third calls {@code analyze()} over and over, and any exception out of {@code analyze()} fails.
 *
 * <p>The arguments are generic (a few shared objects, small ints, the calling thread), so the
 * recording paths reached are the ones such arguments reach; a recording call that rejects them is
 * ignored, since only analysis is under test. The floor at the end keeps the harness honest: if
 * most detectors stopped being reached, a green run would mean nothing.
 */
class DetectorAnalysisUnderConcurrentWritesTest {

    /** How long the writers record into one detector while it is analysed. */
    private static final Duration WINDOW = Duration.ofMillis(120);

    /** A cap on calls per writer, so append-only state stays small enough to analyse quickly. */
    private static final int MAX_CALLS = 3_000;

    /** Method name prefixes that read, configure or tear down rather than record. */
    private static final List<String> NOT_RECORDING = List.of(
            "analyze", "analyse", "validate", "reset", "clear", "enable", "disable", "get", "is",
            "has", "to", "set", "equals", "hash", "wait", "notify", "report", "summar", "describe",
            "with", "close", "await", "check", "find", "count", "snapshot", "format", "compare");

    @Test
    @DisplayName("no detector's analyze() throws while two threads are still recording into it")
    void analysisSurvivesConcurrentRecording() throws Exception {
        AsyncTestConfig config = AsyncTestConfig.builder().detectAll(true).build();
        DetectorRegistry registry = new DetectorRegistry(config);

        Map<String, String> failures = new TreeMap<>();
        Set<String> reached = new HashSet<>();
        for (Object detector : registry.instances().values()) {
            Method analyze = analyzeMethod(detector.getClass());
            List<Method> recorders = recordingMethods(detector.getClass());
            if (analyze == null || recorders.isEmpty()) {
                continue;
            }
            String name = detector.getClass().getSimpleName();
            Throwable thrown = exercise(detector, analyze, recorders, reached);
            if (thrown != null) {
                failures.put(name, thrown.toString());
            }
        }

        assertTrue(failures.isEmpty(),
                "analyze() threw while recording was still under way. A worker past the quiesce "
                        + "grace makes this a real run, and ifIssue then drops the whole report. "
                        + "Snapshot the state at the start of analyze (a copy under the detector's "
                        + "lock, or a concurrent collection):\n  "
                        + String.join("\n  ", failures.entrySet().stream()
                                .map(e -> e.getKey() + ": " + e.getValue()).toList()));
        assertTrue(reached.size() >= 100,
                "only " + reached.size() + " detectors accepted a generated recording call, so this "
                        + "harness no longer exercises most of them: " + new java.util.TreeSet<>(reached));
    }

    /** Records from two threads until the window closes, analysing on a third; returns what analysis threw. */
    private static Throwable exercise(Object detector, Method analyze, List<Method> recorders,
                                      Set<String> reached) throws InterruptedException {
        Map<Class<?>, Object> shared = new HashMap<>();
        AtomicBoolean writing = new AtomicBoolean(true);
        AtomicBoolean accepted = new AtomicBoolean();
        AtomicReference<Throwable> analysisFailure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(3);
        long deadline = System.nanoTime() + WINDOW.toNanos();

        Runnable writer = () -> {
            await(started);
            for (int i = 0; i < MAX_CALLS && System.nanoTime() < deadline; i++) {
                Method m = recorders.get(i % recorders.size());
                Object[] args = arguments(m, i, shared);
                if (args == null) {
                    continue;
                }
                try {
                    m.invoke(detector, args);
                    accepted.set(true);
                } catch (InvocationTargetException | IllegalAccessException | IllegalArgumentException rejected) {
                    // Only analysis is under test; a recording call the generic arguments upset is skipped.
                }
            }
        };
        Thread w1 = new Thread(writer, "writer-1");
        Thread w2 = new Thread(writer, "writer-2");
        Thread analyser = new Thread(() -> {
            await(started);
            while (writing.get() && analysisFailure.get() == null) {
                try {
                    analyze.invoke(detector);
                } catch (InvocationTargetException e) {
                    analysisFailure.compareAndSet(null, e.getCause());
                } catch (IllegalAccessException e) {
                    analysisFailure.compareAndSet(null, e);
                }
            }
        }, "analyser");
        w1.setDaemon(true);
        w2.setDaemon(true);
        analyser.setDaemon(true);
        w1.start();
        w2.start();
        analyser.start();
        w1.join(10_000);
        w2.join(10_000);
        writing.set(false);
        analyser.join(10_000);
        synchronized (shared) {
            for (Object made : shared.values()) {
                if (made instanceof java.util.concurrent.ExecutorService executor) {
                    executor.shutdownNow();
                } else if (made instanceof java.util.Timer timer) {
                    timer.cancel();
                }
            }
        }
        if (accepted.get()) {
            reached.add(detector.getClass().getSimpleName());
        }
        return analysisFailure.get();
    }

    private static void await(CountDownLatch latch) {
        latch.countDown();
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Method analyzeMethod(Class<?> type) {
        for (String name : List.of("analyze", "validateConstructorSafety", "validateLockOrder")) {
            try {
                Method m = type.getMethod(name);
                if (!Modifier.isStatic(m.getModifiers())) {
                    return m;
                }
            } catch (NoSuchMethodException absent) {
                // try the next name
            }
        }
        return null;
    }

    private static List<Method> recordingMethods(Class<?> type) {
        List<Method> recorders = new ArrayList<>();
        for (Method m : type.getMethods()) {
            if (m.getDeclaringClass() == Object.class || Modifier.isStatic(m.getModifiers())
                    || m.isSynthetic() || m.isBridge()) {
                continue;
            }
            String lower = m.getName().toLowerCase(java.util.Locale.ROOT);
            if (NOT_RECORDING.stream().anyMatch(lower::startsWith) || m.getParameterCount() == 0) {
                continue;
            }
            recorders.add(m);
        }
        recorders.sort(Comparator.comparing(Method::toGenericString));
        return recorders;
    }

    /** {@return arguments for {@code m} made from its parameter types, or null if one has no recipe} */
    private static Object[] arguments(Method m, int i, Map<Class<?>, Object> shared) {
        Class<?>[] types = m.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int p = 0; p < types.length; p++) {
            Object value = argument(types[p], i + p, shared);
            if (value == NO_RECIPE) {
                return null;
            }
            args[p] = value;
        }
        return args;
    }

    private static final Object NO_RECIPE = new Object();

    private static final Map<Class<?>, Supplier<Object>> SHAREABLE = Map.ofEntries(
            Map.entry(Object.class, Object::new),
            Map.entry(Map.class, HashMap::new),
            Map.entry(List.class, ArrayList::new),
            Map.entry(Collection.class, ArrayList::new),
            Map.entry(Set.class, HashSet::new),
            Map.entry(Queue.class, ConcurrentLinkedQueue::new),
            Map.entry(Lock.class, ReentrantLock::new),
            Map.entry(ReentrantLock.class, ReentrantLock::new),
            Map.entry(ReadWriteLock.class, ReentrantReadWriteLock::new),
            Map.entry(ReentrantReadWriteLock.class, ReentrantReadWriteLock::new),
            Map.entry(CompletableFuture.class, CompletableFuture::new),
            Map.entry(AtomicInteger.class, AtomicInteger::new),
            Map.entry(AtomicLong.class, AtomicLong::new),
            Map.entry(AtomicReference.class, AtomicReference::new),
            Map.entry(StringBuilder.class, StringBuilder::new),
            Map.entry(java.util.concurrent.locks.Condition.class, () -> new ReentrantLock().newCondition()),
            Map.entry(java.util.concurrent.Semaphore.class, () -> new java.util.concurrent.Semaphore(1)),
            Map.entry(java.util.concurrent.CountDownLatch.class, () -> new CountDownLatch(1)),
            Map.entry(java.util.concurrent.CyclicBarrier.class, () -> new java.util.concurrent.CyclicBarrier(2)),
            Map.entry(java.util.concurrent.Phaser.class, () -> new java.util.concurrent.Phaser(1)),
            Map.entry(java.util.concurrent.Exchanger.class, java.util.concurrent.Exchanger::new),
            Map.entry(java.util.concurrent.BlockingQueue.class, java.util.concurrent.LinkedBlockingQueue::new),
            Map.entry(java.util.concurrent.ExecutorService.class, java.util.concurrent.Executors::newSingleThreadExecutor),
            Map.entry(java.util.concurrent.ScheduledExecutorService.class,
                    () -> java.util.concurrent.Executors.newSingleThreadScheduledExecutor()),
            Map.entry(java.util.concurrent.ThreadFactory.class, java.util.concurrent.Executors::defaultThreadFactory),
            Map.entry(ThreadLocal.class, ThreadLocal::new),
            Map.entry(java.util.Timer.class, () -> new java.util.Timer(true)),
            Map.entry(java.util.TimerTask.class, () -> new java.util.TimerTask() {
                @Override
                public void run() {
                    // nothing to do
                }
            }),
            Map.entry(java.util.Calendar.class, java.util.Calendar::getInstance),
            Map.entry(java.text.SimpleDateFormat.class, java.text.SimpleDateFormat::new),
            Map.entry(java.util.Random.class, java.util.Random::new),
            Map.entry(java.util.random.RandomGenerator.class, java.util.Random::new),
            Map.entry(java.util.concurrent.ThreadLocalRandom.class, java.util.concurrent.ThreadLocalRandom::current),
            Map.entry(java.util.SplittableRandom.class, java.util.SplittableRandom::new),
            Map.entry(java.security.SecureRandom.class, java.security.SecureRandom::new),
            Map.entry(java.util.function.BooleanSupplier.class, () -> (java.util.function.BooleanSupplier) () -> true),
            Map.entry(AutoCloseable.class, () -> (AutoCloseable) () -> { }),
            Map.entry(java.io.Closeable.class, () -> (java.io.Closeable) () -> { }),
            Map.entry(java.io.PipedInputStream.class, java.io.PipedInputStream::new),
            Map.entry(java.util.zip.Checksum.class, java.util.zip.CRC32::new),
            Map.entry(java.util.zip.Deflater.class, java.util.zip.Deflater::new),
            Map.entry(java.util.zip.Inflater.class, java.util.zip.Inflater::new),
            Map.entry(java.nio.charset.CharsetEncoder.class, () -> java.nio.charset.StandardCharsets.UTF_8.newEncoder()),
            Map.entry(java.nio.charset.CharsetDecoder.class, () -> java.nio.charset.StandardCharsets.UTF_8.newDecoder()),
            Map.entry(javax.crypto.Mac.class, () -> unchecked(() -> javax.crypto.Mac.getInstance("HmacSHA256"))),
            Map.entry(javax.crypto.Cipher.class, () -> unchecked(() -> javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding"))),
            Map.entry(java.security.Signature.class, () -> unchecked(() -> java.security.Signature.getInstance("SHA256withRSA"))),
            Map.entry(java.security.MessageDigest.class, () -> unchecked(() -> java.security.MessageDigest.getInstance("SHA-256"))));

    /** Types whose instances start threads, so a fresh one per call would leak them. */
    private static final Set<Class<?>> OWNS_A_THREAD = Set.of(
            java.util.concurrent.ExecutorService.class,
            java.util.concurrent.ScheduledExecutorService.class,
            java.util.Timer.class);

    private static Object unchecked(java.util.concurrent.Callable<Object> make) {
        try {
            return make.call();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object argument(Class<?> type, int i, Map<Class<?>, Object> shared) {
        int small = Math.floorMod(i, 4);
        if (type == int.class || type == Integer.class) {
            return small;
        }
        if (type == long.class || type == Long.class) {
            return (long) small;
        }
        if (type == boolean.class || type == Boolean.class) {
            return small % 2 == 0;
        }
        if (type == double.class || type == Double.class) {
            return (double) small;
        }
        if (type == String.class || type == CharSequence.class) {
            // A new key on every call: state keyed by name must keep growing while it is
            // analysed, or a detector's maps settle after a few calls and a torn read never shows.
            return "key-" + i;
        }
        if (type == Thread.class) {
            return Thread.currentThread();
        }
        if (type == Runnable.class) {
            return (Runnable) () -> { };
        }
        if (type == Class.class) {
            return small % 2 == 0 ? Object.class : String.class;
        }
        if (type == Duration.class) {
            return Duration.ofMillis(small);
        }
        if (type == Instant.class) {
            return Instant.now();
        }
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (type == Throwable.class || type == Exception.class || type == RuntimeException.class) {
            return new RuntimeException("generated");
        }
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            return constants.length == 0 ? NO_RECIPE : constants[small % constants.length];
        }
        if (type.isArray()) {
            return java.lang.reflect.Array.newInstance(type.getComponentType(), 0);
        }
        Supplier<Object> recipe = SHAREABLE.get(type);
        if (recipe != null && small == 3 && !OWNS_A_THREAD.contains(type)) {
            // Every fourth call, a fresh instance, so state keyed by identity grows too.
            return recipe.get();
        }
        if (recipe != null) {
            synchronized (shared) {
                // Two instances per type, so a detector sees both one object shared by threads and
                // a second one, without the state growing per call.
                return shared.computeIfAbsent(small % 2 == 0 ? type : type.arrayType(),
                        k -> recipe.get());
            }
        }
        return NO_RECIPE;
    }
}
