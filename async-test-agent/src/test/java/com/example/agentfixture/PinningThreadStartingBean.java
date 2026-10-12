package com.example.agentfixture;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts virtual threads that pin, the way a test body that runs its own virtual threads does,
 * recording nothing.
 *
 * <p>A thread pins when it blocks inside a class initializer, on every JDK this project builds on.
 * Each pin initializes a fresh copy of {@link SleepsInItsInitializer} in a loader of its own, so the
 * same initializer pins again in every round instead of once per JVM. Woven with
 * {@code collections=true}, the {@code Thread.Builder.start} here reaches the thread hooks, which
 * attribute the started thread to the run of the thread that started it.
 */
public class PinningThreadStartingBean {

    /** How long one pin lasts. */
    private static final long PIN_MILLIS = 20;

    /** Classes the fresh loaders delegated, so only the first one asks the parent and its locks. */
    private static final ConcurrentHashMap<String, Class<?>> DELEGATED = new ConcurrentHashMap<>();

    /** Initializers run, so a test can tell a thread that pinned from one that never got going. */
    public static final java.util.concurrent.atomic.AtomicInteger PINS = new java.util.concurrent.atomic.AtomicInteger();

    /** Its initializer sleeps, which pins the virtual thread running it. Public JDK API only. */
    public static final class SleepsInItsInitializer {
        static {
            try {
                Thread.sleep(PIN_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private SleepsInItsInitializer() {
        }
    }

    /**
     * Starts a virtual thread that pins once, and waits for it.
     *
     * @throws InterruptedException if interrupted while joining
     */
    public void startVirtualThreadThatPins() throws InterruptedException {
        Thread.ofVirtual().start(PinningThreadStartingBean::pinOnce).join();
    }

    /**
     * Starts a platform thread that, until {@code stop} is set, keeps starting a fresh virtual
     * thread that pins once: what a test running in parallel does, through the same woven
     * {@code Thread.Builder.start}, from a thread that is no run's worker.
     *
     * @param stop set to end the thread
     * @return the started thread
     */
    public Thread startPinningUntil(AtomicBoolean stop) {
        return Thread.ofPlatform().daemon().start(() -> {
            while (!stop.get()) {
                try {
                    Thread.ofVirtual().start(PinningThreadStartingBean::pinOnce).join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    /** Initializes a fresh copy of {@link SleepsInItsInitializer}, running its initializer. */
    static void pinOnce() {
        Class<?> type = SleepsInItsInitializer.class;
        String name = type.getName();
        byte[] bytes;
        try (InputStream in = type.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            bytes = Objects.requireNonNull(in, name).readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ClassLoader loader = new ClassLoader(type.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
                if (!className.equals(name)) {
                    Class<?> known = DELEGATED.get(className);
                    if (known == null) {
                        known = super.loadClass(className, resolve);
                        DELEGATED.putIfAbsent(className, known);
                    }
                    return known;
                }
                synchronized (getClassLoadingLock(className)) {
                    Class<?> loaded = findLoadedClass(className);
                    return loaded != null ? loaded : defineClass(className, bytes, 0, bytes.length);
                }
            }
        };
        try {
            Class.forName(name, true, loader);
            PINS.incrementAndGet();
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
