package se.deversity.asynctest;

import java.time.Duration;
import java.util.Locale;
import java.util.TimeZone;

import org.jspecify.annotations.Nullable;

import se.deversity.asynctest.diagnostics.HeldLocks;
import se.deversity.asynctest.diagnostics.SleepInLockDetector;
import se.deversity.asynctest.diagnostics.SystemPropertyMutationDetector;
import se.deversity.vibetags.annotations.AIContract;

/**
 * Hooks for the static calls a detector's input maps onto.
 *
 * <h2>Why a sleep needs the lockset rather than a stack walk</h2>
 *
 * <p>{@code SleepInLockDetector} reports a {@code Thread.sleep} performed while a lock is held:
 * throughput collapses to one caller per sleep and every other caller queues behind it. Answering
 * "was a lock held" has always been the hard half - a stack trace does not say, and asking
 * {@code Thread.holdsLock} requires already knowing which object to ask about.
 *
 * <p>The agent has the answer for free. {@link se.deversity.asynctest.AgentLockHooks} maintains the
 * per-thread lockset that the woven {@code MONITORENTER} instructions and the substituted
 * {@code Lock.lock()} calls both feed, so {@link HeldLocks#anyHeld()} is a field read on the
 * calling thread. This hook is the two halves meeting: the sleep is seen by substitution, and
 * whether it mattered is answered by the lockset that was already there.
 *
 * <h2>What is deliberately not recorded</h2>
 *
 * <p>A sleep with no lock held is not the bug and records nothing. Rate limiting, back-off and
 * polling are all ordinary uses of {@code Thread.sleep}, and reporting them would make the finding
 * worthless.
 *
 * @since 1.10.0
 */
@AIContract(reason = "Called from bytecode the agent rewrites, through the static substitution path: the method name and erased signature here are matched by CollectionAccessWeaver.STATIC_ENTRIES and cannot change independently of it. The guard is the point - recording only when the thread's lockset holds a lock the detector can confirm is what separates the bug from rate limiting, back-off and polling, which are ordinary uses of Thread.sleep and vastly more common. Record before sleeping, not after: a sleep interrupted mid-way still held the lock for as long as it lasted, and the finding is about the holding rather than the completing. The hook must perform the original sleep and propagate InterruptedException unchanged. It must never call the single-argument recordSleep: that one resolves the held monitors through ThreadMXBean, which does not report virtual threads, so on the runner's default workers it answers none and the detector never fires. The plain forms must hand the detector the whole lockset (recordSleepUnderHeldLocks) rather than its top entry, which dropped a StampedLock on top and a confirmable lock below an unconfirmable one (#543). A sub-millisecond sleep must round up to one rather than truncate to zero: the detector drops anything at or below zero, so truncating turns a real lock-holding pause into the same silence an unwoven call site produces. The process-global writes (System.setProperty and clearProperty, Locale.setDefault, TimeZone.setDefault) are the same kind of static substitution: each records the write before performing it, so the lockset the detector probes is the one the write ran under, and returns what the JDK method returned.")
public final class AgentSleepHooks {

    private AgentSleepHooks() {
    }

    /**
     * Weaves {@code Thread.sleep(long)}.
     *
     * @param millis how long to sleep
     * @throws InterruptedException if interrupted while sleeping
     */
    public static void sleep(long millis) throws InterruptedException {
        AgentConcurrencyUtilHooks.blocking("Thread.sleep", false);
        // Never recordSleep(millis): that one asks ThreadMXBean which monitors the thread holds,
        // and ThreadMXBean does not report virtual threads - which the runner uses by default, so
        // it would answer "none" for every worker and the finding would never fire. The lockset
        // walk confirms each entry through Thread.holdsLock or the lock's own owner instead.
        recordUnderLockset(millis);
        Thread.sleep(millis);
    }

    /**
     * Weaves {@code Thread.sleep(long)} inside a {@code synchronized} method.
     *
     * <p>A {@code synchronized} method takes its monitor from the {@code ACC_SYNCHRONIZED} access
     * flag, not from an instruction, so for #388 nothing in the body told {@link HeldLocks} the
     * lock was held. The weaver knows, at weave time, both that the enclosing method is
     * synchronized and what it locks - {@code this} for an instance method and the class for a
     * static one - so it loads that monitor and calls this overload instead. One more value on the
     * stack, no branch and no handler, which is what {@code COMPUTE_MAXS, never COMPUTE_FRAMES}
     * permits. Since #822 the lockset learns the monitor too, at the method's entry, which is what
     * lets a sleep in a helper the method calls be seen through {@link #sleep(long)}; this overload
     * still names the monitor directly, so the report names the method's own lock.
     *
     * <p>There is no guard here, and there must not be: this method is only ever reached from
     * inside a {@code synchronized} method, so the monitor is held by construction.
     *
     * @param millis  how long to sleep
     * @param monitor the monitor the enclosing synchronized method holds
     * @throws InterruptedException if interrupted while sleeping
     * @since 1.11.0
     */
    public static void sleepHoldingMonitor(long millis, Object monitor) throws InterruptedException {
        AgentConcurrencyUtilHooks.blocking("Thread.sleep", false);
        recordHeld(millis, monitor);
        Thread.sleep(millis);
    }

    /**
     * Weaves {@code Thread.sleep(Duration)} outside a synchronized method.
     *
     * <p>The form new code writes since JDK 19, and it was not woven at all while its
     * {@code long} sibling was (#440). Whether a sleep is a bug depends on whether a lock was
     * held, not on which overload expressed the duration.
     *
     * <p>A duration under a millisecond is recorded as one millisecond rather than dropped; the
     * reason is on {@link #recordableMillis(Duration)}.
     *
     * @param duration how long to sleep
     * @throws InterruptedException if interrupted while sleeping
     */
    public static void sleep(Duration duration) throws InterruptedException {
        AgentConcurrencyUtilHooks.blocking("Thread.sleep", false);
        recordUnderLockset(recordableMillis(duration));
        Thread.sleep(duration);
    }

    /**
     * Weaves {@code Thread.sleep(Duration)} inside a synchronized method.
     *
     * @param duration how long to sleep
     * @param monitor  the enclosing method's monitor, loaded at the call site by the weaver
     * @throws InterruptedException if interrupted while sleeping
     */
    public static void sleepHoldingMonitor(Duration duration, Object monitor)
            throws InterruptedException {
        AgentConcurrencyUtilHooks.blocking("Thread.sleep", false);
        recordHeld(recordableMillis(duration), monitor);
        Thread.sleep(duration);
    }

    /**
     * Weaves {@code Thread.sleep(long, int)} outside a synchronized method.
     *
     * <p>The nanosecond argument adds nothing to the finding while the millisecond part is
     * positive: the detector's question is how long a lock went un-progressed, and no lock is held
     * meaningfully differently for an extra 999999 nanoseconds. It decides the finding when the
     * millisecond part is zero, which is what {@link #recordableMillis(long, int)} is for.
     *
     * @param millis how long to sleep, in milliseconds
     * @param nanos  the additional nanoseconds to sleep
     * @throws InterruptedException if interrupted while sleeping
     */
    public static void sleep(long millis, int nanos) throws InterruptedException {
        AgentConcurrencyUtilHooks.blocking("Thread.sleep", false);
        recordUnderLockset(recordableMillis(millis, nanos));
        Thread.sleep(millis, nanos);
    }

    /**
     * Weaves {@code Thread.sleep(long, int)} inside a synchronized method.
     *
     * @param millis  how long to sleep, in milliseconds
     * @param nanos   the additional nanoseconds to sleep
     * @param monitor the enclosing method's monitor, loaded at the call site by the weaver
     * @throws InterruptedException if interrupted while sleeping
     */
    public static void sleepHoldingMonitor(long millis, int nanos, Object monitor)
            throws InterruptedException {
        AgentConcurrencyUtilHooks.blocking("Thread.sleep", false);
        recordHeld(recordableMillis(millis, nanos), monitor);
        Thread.sleep(millis, nanos);
    }

    /**
     * Weaves {@code System.setProperty(String, String)}: the write is recorded with the locks the
     * thread holds, then performed.
     *
     * @param key   the property key
     * @param value the new value
     * @return the previous value
     * @since 1.13.2
     */
    public static @Nullable String systemSetProperty(String key, String value) {
        SystemPropertyMutationDetector detector = AsyncTestContext.currentSystemPropertyMutationDetector();
        if (detector != null) {
            detector.recordSet(key, value, Thread.currentThread());
        }
        return System.setProperty(key, value);
    }

    /**
     * Weaves {@code System.clearProperty(String)}, as {@link #systemSetProperty}.
     *
     * @param key the property key
     * @return the removed value
     * @since 1.13.2
     */
    public static @Nullable String systemClearProperty(String key) {
        SystemPropertyMutationDetector detector = AsyncTestContext.currentSystemPropertyMutationDetector();
        if (detector != null) {
            detector.recordClear(key, Thread.currentThread());
        }
        return System.clearProperty(key);
    }

    /**
     * Weaves {@code Locale.setDefault(Locale)}: the JVM's default locale, which every
     * {@code String.format}, {@code DateFormat} and {@code NumberFormat} without an explicit one
     * reads, recorded as a write of the key {@code default Locale}.
     *
     * @param locale the new default
     * @since 1.13.2
     */
    public static void localeSetDefault(Locale locale) {
        recordDefault(DEFAULT_LOCALE, String.valueOf(locale));
        Locale.setDefault(locale);
    }

    /**
     * Weaves {@code Locale.setDefault(Locale.Category, Locale)}, as {@link #localeSetDefault(Locale)},
     * keyed by the category.
     *
     * @param category the category whose default changes
     * @param locale   the new default
     * @since 1.13.2
     */
    public static void localeSetDefault(Locale.Category category, Locale locale) {
        recordDefault(category == Locale.Category.DISPLAY ? DEFAULT_LOCALE_DISPLAY : DEFAULT_LOCALE_FORMAT,
                String.valueOf(locale));
        Locale.setDefault(category, locale);
    }

    /**
     * Weaves {@code TimeZone.setDefault(TimeZone)}, recorded as a write of the key
     * {@code default TimeZone}; {@code null}, which restores the zone the JVM started with, is a
     * clear.
     *
     * @param zone the new default, or {@code null}
     * @since 1.13.2
     */
    public static void timeZoneSetDefault(@Nullable TimeZone zone) {
        recordDefault(DEFAULT_TIME_ZONE, zone == null ? null : zone.getID());
        TimeZone.setDefault(zone);
    }

    /** The key a write of the JVM's default locale is recorded under. */
    static final String DEFAULT_LOCALE = "default Locale";

    /** The key a write of the default display locale is recorded under. */
    static final String DEFAULT_LOCALE_DISPLAY = "default Locale (DISPLAY)";

    /** The key a write of the default format locale is recorded under. */
    static final String DEFAULT_LOCALE_FORMAT = "default Locale (FORMAT)";

    /** The key a write of the JVM's default time zone is recorded under. */
    static final String DEFAULT_TIME_ZONE = "default TimeZone";

    private static void recordDefault(String key, @Nullable String value) {
        SystemPropertyMutationDetector detector = AsyncTestContext.currentSystemPropertyMutationDetector();
        if (detector == null) {
            return;
        }
        if (value == null) {
            detector.recordClear(key, Thread.currentThread());
        } else {
            detector.recordSet(key, value, Thread.currentThread());
        }
    }

    /**
     * Records a sleep that happened with {@code monitor} held.
     *
     * <p>Always the two-argument {@code recordSleep}, never the one-argument overload. That one
     * resolves the held monitors through {@code ThreadMXBean}, which does not report virtual
     * threads - and the runner uses them by default, so it would answer "none" for every worker
     * and the detector would never fire. Naming the monitor routes it through
     * {@code Thread.holdsLock} instead, which has no such blind spot.
     *
     * @param millis  how long the sleep was, in milliseconds
     * @param monitor the monitor held across it
     */
    private static void recordHeld(long millis, Object monitor) {
        SleepInLockDetector detector = AsyncTestContext.currentSleepInLockDetector();
        if (detector != null) {
            detector.recordSleep(millis, monitor);
        }
    }

    /**
     * Records a sleep against whichever lock on the calling thread's lockset can be confirmed.
     *
     * <p>Never the top entry alone: a {@code StampedLock} on top cannot be confirmed through the
     * caller-named overload, and a confirmable lock can sit below an unconfirmable one, and both
     * were dropped while this passed {@code topHeld()} (#543). The detector walks the set and
     * still confirms every entry against the lock itself.
     *
     * @param millis how long the sleep was, in milliseconds
     */
    private static void recordUnderLockset(long millis) {
        if (!HeldLocks.anyHeld()) {
            return;
        }
        SleepInLockDetector detector = AsyncTestContext.currentSleepInLockDetector();
        if (detector != null) {
            detector.recordSleepUnderHeldLocks(millis);
        }
    }

    /**
     * {@return the milliseconds to record for a sleep of {@code millis} plus {@code nanos}}
     *
     * <p>Rounded up when the millisecond part is zero, rather than truncated. {@code recordSleep}
     * drops anything at or below zero, so truncating would make {@code sleep(0, 500_000)} under a
     * lock record nothing - silence indistinguishable from an unwoven call site, which is the one
     * failure this weaving surface exists to remove. The sleep is real: on JDK 26,
     * {@code sleep(0, 500_000)} blocks for about 1.5ms against about 17us for {@code sleep(0)}.
     *
     * <p>Nothing is added when the millisecond part is already positive. One unit across all four
     * overloads is worth more than 999999 nanoseconds of precision the detector cannot use.
     *
     * @param millis the milliseconds the caller asked for
     * @param nanos  the additional nanoseconds the caller asked for
     */
    private static long recordableMillis(long millis, int nanos) {
        return millis > 0 || nanos <= 0 ? millis : 1L;
    }

    /**
     * {@return the milliseconds to record for a sleep of {@code duration}}
     *
     * <p>Rounded up for the reason {@link #recordableMillis(long, int)} gives. Null-tolerant
     * because a {@code null} here is the caller's {@code NullPointerException} to receive from
     * {@code Thread.sleep}, not this hook's to throw first out of a recording path.
     *
     * @param duration the duration the caller asked for
     */
    private static long recordableMillis(@Nullable Duration duration) {
        if (duration == null) {
            return 0L;
        }
        long millis = duration.toMillis();
        return millis > 0 || duration.isZero() || duration.isNegative() ? millis : 1L;
    }
}
