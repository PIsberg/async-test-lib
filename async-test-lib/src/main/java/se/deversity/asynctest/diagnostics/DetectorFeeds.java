package se.deversity.asynctest.diagnostics;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.asynctest.DetectorType;
import se.deversity.vibetags.annotations.AIKeepInSync;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The feed of every built-in detector: what has to happen before it can produce a finding.
 *
 * <p><strong>Why this exists.</strong> The corpus eval attached the agent to 42 unmodified
 * third-party classes under {@code detectAll = true} and exactly two detectors of
 * {@value DetectorTrust#DETECTOR_COUNT} produced findings. That is a property of the feeds, not a
 * defect: most detectors are told what happened by the test body, some watch the JVM on their
 * own, and two read the agent's woven streams. A user attaching the agent to an existing suite is
 * buying exactly the {@link DetectorFeed#AGENT} set plus whatever {@link DetectorFeed#ZERO_CONFIG}
 * already measured, and until this table existed that set was nowhere enumerated.
 *
 * <p><strong>How the table is kept honest.</strong> Membership is by exception: every detector is
 * {@link DetectorFeed#RECORDING} unless listed here, so the table cannot silently miss a type.
 * The {@link DetectorFeed#AGENT} rows are pinned to the classes the woven streams are
 * compile-wired into, and {@code DetectorFeedCoverageTest} fails on a row that drifts from that
 * wiring or from the listing in {@code docs/DETECTOR_CATALOG.md}. The
 * {@link DetectorFeed#ZERO_CONFIG} rows carry the same weight a {@code PROMPT} trust tier does:
 * stated, reviewed, and cheap to challenge, with the deciding source named in the catalog.
 *
 * @since 1.9.8
 */
@AIKeepInSync(
    mirrors = {
        "se.deversity.asynctest.DetectorType",
        "se.deversity.asynctest.diagnostics.DetectorTrust",
        "docs/DETECTOR_CATALOG.md"
    },
    reason = "Every DetectorType resolves to exactly one feed, the AGENT set must equal the "
           + "detectors the telemetry bridge and collection hooks are compile-wired into, and the "
           + "catalog's feed listing must name the same classes this table classifies. A feed "
           + "that drifts misleads the user deciding whether attaching the agent buys them "
           + "anything.",
    enforcedBy = "se.deversity.asynctest.architecture.DetectorFeedCoverageTest"
)
@API(status = Status.EXPERIMENTAL)
public final class DetectorFeeds {

    /** Fed by the agent's woven field, collection and lock streams. */
    private static final Set<DetectorType> AGENT_FED = EnumSet.of(
            DetectorType.ATOMICITY_VIOLATIONS,
            DetectorType.SHARED_COLLECTIONS,
            // The lock substitutions were reaching HeldLocks only, which answers "was this
            // access guarded". These three ask different questions of the same event stream and
            // were reachable by hand-written record calls alone, so attaching the agent and
            // writing no instrumentation produced silence from them on genuinely inverted lock
            // order. AgentLockHooks now delivers to each.
            DetectorType.LOCK_ORDER,
            DetectorType.LOCK_LEAKS,
            DetectorType.TRY_LOCK_MISUSE,
            // The shared-instance family. Each of these three JDK types keeps mutable state, is
            // documented as unsafe to share, and is routinely cached in a field because building
            // one is expensive - which is how a confined object becomes a shared one. The
            // substitution sees the call site, which is the only place the instance and the
            // calling thread are both in hand.
            DetectorType.SIMPLE_DATE_FORMAT,
            DetectorType.SHARED_MATCHER,
            DetectorType.SHARED_MESSAGE_DIGEST,
            DetectorType.CALENDAR,
            DetectorType.STRING_BUILDER,
            DetectorType.SHARED_DECIMAL_FORMAT,
            DetectorType.SHARED_FORMATTER,
            // The coordination primitives. Sharing is the point of these, so what the detectors
            // report is protocol misuse - a permit that never came back, an offer whose false
            // return was discarded. Nobody instruments a semaphore three layers down in the
            // class under test, which is why these were unreachable in practice.
            DetectorType.SEMAPHORE,
            DetectorType.COUNTDOWN_LATCH,
            DetectorType.LATCH_MISUSE,
            DetectorType.BLOCKING_QUEUE,
            // Thread.sleep is the agent's first static substitution. Whether a sleep is a bug
            // depends entirely on whether a lock was held, which the lockset already knew and no
            // stack trace records, so the two halves only had to be introduced.
            DetectorType.SLEEP_IN_LOCK);

    /**
     * Fed by the JVM and the harness with no recording call.
     *
     * <p>Three, each with the deciding source: {@code DeadlockDetector.analyze()} samples
     * {@code ThreadMXBean.findDeadlockedThreads()} on its own; {@code ConcurrencyRunner} pushes a
     * thread-dump snapshot into {@code LivelockDetector} for every worker of every round; and
     * {@code StaticInitDeadlockDetector.analyze()} walks the live stacks for threads parked in
     * {@code <clinit>} frames. Nothing else in the harness feeds a detector by itself: the sweep
     * behind #300 checked every {@code record*}/{@code register*} call site in main code.
     */
    private static final Set<DetectorType> ZERO_CONFIG_FED = EnumSet.of(
            DetectorType.DEADLOCKS,
            DetectorType.LIVELOCKS,
            DetectorType.STATIC_INIT_DEADLOCK);

    private static final Map<DetectorType, DetectorFeed> FEEDS = buildTable();

    private DetectorFeeds() {
    }

    private static Map<DetectorType, DetectorFeed> buildTable() {
        Map<DetectorType, DetectorFeed> feeds = new EnumMap<>(DetectorType.class);
        for (DetectorType type : DetectorType.values()) {
            feeds.put(type, DetectorFeed.RECORDING);
        }
        for (DetectorType type : ZERO_CONFIG_FED) {
            feeds.put(type, DetectorFeed.ZERO_CONFIG);
        }
        for (DetectorType type : AGENT_FED) {
            feeds.put(type, DetectorFeed.AGENT);
        }
        return feeds;
    }

    /**
     * {@return what feeds {@code type}}
     *
     * @param type the detector
     */
    public static DetectorFeed feedOf(DetectorType type) {
        // The table is total by construction; the default restates it for the null checker.
        return FEEDS.getOrDefault(type, DetectorFeed.RECORDING);
    }

    /** {@return the detectors fed by the given feed, in declaration order} */
    public static Set<DetectorType> fedBy(DetectorFeed feed) {
        Set<DetectorType> matching = EnumSet.noneOf(DetectorType.class);
        for (Map.Entry<DetectorType, DetectorFeed> entry : FEEDS.entrySet()) {
            if (entry.getValue() == feed) {
                matching.add(entry.getKey());
            }
        }
        return matching;
    }
}
