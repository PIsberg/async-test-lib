package se.deversity.asynctest.example.service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Models a {@code LazyConstant<String>}-backed lazy config holder (JDK 26, second
 * preview — the renamed, simplified successor of the JDK 25 {@code StableValue}).
 *
 * <p>The real JDK 26 type is {@code java.lang.LazyConstant}, created with
 * {@code LazyConstant.of(supplier)}: the supplier runs at most once on first
 * {@code get()}, later gets return the cached value, and null results throw
 * {@code NullPointerException}. Because that type is a preview API not present on
 * the Java 21 baseline this example targets, the holder here is modeled with an
 * {@link AtomicReference} that mirrors the same semantics.
 *
 * <p>The class deliberately exposes both the <em>correct</em> access pattern
 * ({@link #get()}) and a <em>buggy</em> hand-rolled variant
 * ({@link #getRacy()}) whose supplier can run more than once, so the test can
 * drive each and surface the difference with {@code LazyConstantMisuseDetector}.
 */
public final class LazyConstantConfigService {

    /** The lazily-computed constant (models LazyConstant&lt;String&gt;). */
    private final AtomicReference<String> value = new AtomicReference<>(null);

    private final Supplier<String> supplier;

    /** Counts real supplier executions — the at-most-once contract under test. */
    private final AtomicInteger supplierRuns = new AtomicInteger(0);

    public LazyConstantConfigService(Supplier<String> supplier) {
        this.supplier = supplier;
    }

    /**
     * Models {@code LazyConstant.get()} — computes at most once, rejects null
     * like the JDK 26 API.
     */
    public String get() {
        String v = value.get();
        if (v != null) {
            return v;
        }
        synchronized (this) {
            v = value.get();
            if (v != null) {
                return v;
            }
            String computed = runSupplier();
            if (computed == null) {
                throw new NullPointerException("LazyConstant supplier returned null");
            }
            value.set(computed);
            return computed;
        }
    }

    /**
     * A hand-rolled "lazy" getter with a classic check-then-act race: two threads
     * can both observe null and both run the supplier — the at-most-once contract
     * the real {@code LazyConstant} would have enforced is silently broken.
     */
    public String getRacy() {
        String v = value.get();
        if (v == null) {                      // BUG: check-then-act, no mutual exclusion
            v = runSupplier();
            value.compareAndSet(null, v);     // loser's supplier work is discarded...
            return v;                         // ...yet still returned — caller sees a value
        }                                     //    that was never stored
        return v;
    }

    private String runSupplier() {
        supplierRuns.incrementAndGet();
        return supplier.get();
    }

    public int supplierRunCount() {
        return supplierRuns.get();
    }
}
