package se.deversity.asynctest.example.service;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Models a {@code StableValue<Config>}-backed lazy config holder (JEP 502).
 *
 * <p>The real JDK 25/26 type is {@code java.lang.StableValue}, a deferred-immutable
 * holder set at most once and then constant-folded by the JVM. Because that type is a
 * preview API not present on the Java 21 baseline this example targets, the holder here
 * is modeled with an {@link AtomicReference} that mirrors the same semantics: unset until
 * the first set, settable once, read via {@link #orElseThrow()} / lazy {@link #orElseSet}.
 *
 * <p>The class deliberately exposes both the <em>buggy</em> access patterns (read before
 * set, double set) and the <em>correct</em> one ({@link #orElseSet}) so the test can drive
 * each and surface the difference with {@code StableValueMisuseDetector}.
 */
public final class StableValueConfigService {

    /** The deferred-immutable holder (models StableValue&lt;String&gt;). */
    private final AtomicReference<String> config = new AtomicReference<>(null);

    /** Models {@code StableValue.orElseThrow()} — throws if read before any set. */
    public String orElseThrow() {
        String v = config.get();
        if (v == null) {
            throw new java.util.NoSuchElementException("config not set");
        }
        return v;
    }

    /** Models {@code StableValue.setOrThrow(value)} — throws on a second set. */
    public void setOrThrow(String value) {
        if (!config.compareAndSet(null, value)) {
            throw new IllegalStateException("config already set");
        }
    }

    /** Models {@code StableValue.orElseSet(supplier)} — lazy, at-most-once, thread-safe. */
    public String orElseSet(Supplier<String> supplier) {
        String v = config.get();
        if (v != null) {
            return v;
        }
        String computed = supplier.get();
        return config.compareAndSet(null, computed) ? computed : config.get();
    }
}
