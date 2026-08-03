package se.deversity.asynctest;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AIPublicAPI;

import java.util.EnumSet;
import java.util.Set;

/**
 * Curated bundles of detectors selectable via {@link AsyncTest#preset()}.
 *
 * <p>{@link AsyncTest} has dozens of boolean detector flags; in practice most
 * test suites want one of a handful of standard combinations. Pick a preset to
 * skip the per-flag configuration, then layer {@code excludes = {...}} on top if
 * you need to opt out of a specific detector.
 *
 * <p>Example:
 * <pre>{@code
 * @AsyncTest(preset = Preset.ESSENTIALS, excludes = DetectorType.LIVELOCKS)
 * void tight_loop_under_stress() { ... }
 * }</pre>
 *
 * <p>{@link #ALL} is the default and preserves the legacy {@code detectAll = true}
 * behavior. The other presets imply {@code detectAll = false} and enable only
 * the listed {@link DetectorType}s.
 *
 * @since 1.6.0
 */
@AIPublicAPI
@AIImmutable(note = "Enum constants — JVM guarantees structural immutability. Internal enabled-set is captured at class init.")
@API(status = Status.STABLE)
public enum Preset {

    /**
     * Run every available detector. Equivalent to the legacy default
     * ({@code @AsyncTest(detectAll = true)}). Picks up new detectors automatically
     * as they are added in future releases. Highest signal, highest cost.
     */
    ALL(null),

    /**
     * High-signal detectors covering the bugs that production teams encounter
     * most often: deadlocks, races, atomicity violations, lock/thread leaks,
     * interrupt mishandling, concurrent modification, and CompletableFuture
     * exception loss. Reasonable default for everyday CI on application code.
     */
    ESSENTIALS(EnumSet.of(
            DetectorType.DEADLOCKS,
            DetectorType.RACE_CONDITIONS,
            DetectorType.LIVELOCKS,
            DetectorType.LOCK_LEAKS,
            DetectorType.THREAD_LEAKS,
            DetectorType.ATOMICITY_VIOLATIONS,
            DetectorType.INTERRUPT_MISHANDLING,
            DetectorType.CONCURRENT_MODIFICATIONS,
            DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS,
            DetectorType.COMPLETABLE_FUTURE_COMPLETION_LEAKS,
            DetectorType.RESOURCE_LEAKS,
            DetectorType.UNCAUGHT_EXCEPTION_HANDLER)),

    /**
     * Same detector set as {@link #ALL}. Listed separately for intent — use
     * STRICT on libraries / SDKs where you want every check on by name in the
     * annotation, independent of whatever ALL evolves to mean in future versions.
     */
    STRICT(null),

    /**
     * Subset of {@link #ESSENTIALS} that omits the heavier detectors (visibility
     * monitoring, virtual-thread carrier exhaustion, etc.) to keep CI runs fast.
     * Designed for pull-request gates where ESSENTIALS would be too slow.
     */
    CI_FAST(EnumSet.of(
            DetectorType.DEADLOCKS,
            DetectorType.RACE_CONDITIONS,
            DetectorType.ATOMICITY_VIOLATIONS,
            DetectorType.LOCK_LEAKS,
            DetectorType.CONCURRENT_MODIFICATIONS,
            DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS)),

    /**
     * Disable every detector. The runner still drives N×M concurrent execution
     * (so the test body still exercises concurrent code) but no diagnostic
     * machinery is engaged. Useful when you want raw stress execution and intend
     * to enable specific detectors via the per-flag boolean attributes manually.
     */
    NONE(EnumSet.noneOf(DetectorType.class));

    // effectively immutable: captured once at class init, stored unmodifiable
    @SuppressWarnings("ImmutableEnumChecker")
    private final @Nullable Set<DetectorType> enabled;

    Preset(@Nullable Set<DetectorType> enabled) {
        this.enabled = enabled == null ? null : Set.copyOf(enabled);
    }

    /**
     * Returns the explicit set of enabled detectors, or {@code null} for {@link #ALL}
     * / {@link #STRICT} which mean "every detector". The {@code null} sentinel
     * lets callers distinguish "use everything available right now" from "use
     * exactly this set", which matters when new detectors are added in future
     * releases.
     *
     * @return the enabled
     */
    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "enabled is already an unmodifiable Set.copyOf snapshot stored in a final field")
    public @Nullable Set<DetectorType> enabled() {
        return enabled;
    }

    /**
     * True when the preset is the legacy default.
     *
     * @return the is all
     */
    public boolean isAll() {
        return this == ALL || this == STRICT;
    }
}
