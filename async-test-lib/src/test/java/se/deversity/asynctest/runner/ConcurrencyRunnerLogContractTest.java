package se.deversity.asynctest.runner;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.E2E;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.ConditionVariableDetector;
import se.deversity.asynctest.diagnostics.DeadlockDetector;

import java.util.List;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import org.junit.platform.testkit.engine.EngineTestKit;

/**
 * The runner's DEBUG events are a contract.
 *
 * <p>Every timing decision in a run is derived from values resolved once inside
 * {@code execute()}: the timeout multiplier, the effective timeout, and the thread count, which
 * stress mode can override so it is not the number on the annotation. None of that is
 * observable from a test's pass or fail, which is exactly why {@code runner.config} exists and
 * why it is asserted here: it is the line that explains a run that behaved differently on CI.
 *
 * <p>Renaming an event or a field asserted here is a breaking change. See CLAUDE.md, "Logging".
 */
@DisplayName("ConcurrencyRunner DEBUG events")
@E2E
class ConcurrencyRunnerLogContractTest {

    private ch.qos.logback.classic.Logger runnerLog;
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;

    @BeforeEach
    void captureRunnerLog() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        runnerLog = context.getLogger(ConcurrencyRunner.class);
        previousLevel = runnerLog.getLevel();
        runnerLog.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        runnerLog.addAppender(appender);
    }

    @AfterEach
    void restore() {
        runnerLog.detachAppender(appender);
        appender.stop();
        runnerLog.setLevel(previousLevel);
    }

    private List<String> events() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private String eventStartingWith(String prefix) {
        return events().stream()
            .filter(m -> m.startsWith(prefix))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no '" + prefix + "' event was logged; got " + events()));
    }

    @Test
    @DisplayName("one run emits the resolved configuration and one event per round")
    void theRunNarratesItsOwnConfiguration() {
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(NarratedDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        String config = eventStartingWith("runner.config");
        assertTrue(config.contains("test=narrated"), "the event names the test: " + config);
        assertTrue(config.contains("threads=3"), "the thread count actually used: " + config);
        assertTrue(config.contains("invocations=2"), "the invocation count: " + config);
        assertTrue(config.contains("effectiveTimeoutMs="),
            "the effective budget every downstream timeout derives from: " + config);
        assertTrue(config.contains("multiplier="),
            "the multiplier that explains a CI-only difference: " + config);

        assertEquals(2, events().stream().filter(m -> m.startsWith("runner.round.start")).count(),
            "one start event per invocation: " + events());
        assertEquals(2, events().stream().filter(m -> m.startsWith("runner.round.done")).count(),
            "one done event per invocation: " + events());
        assertTrue(eventStartingWith("runner.round.start").contains("seed="),
            "every round carries the replay seed, which is the reproduction handle");
        assertTrue(eventStartingWith("runner.round.done").contains("durationMs="),
            "the round reports what it cost");
    }

    @Test
    @DisplayName("the first run without the agent announces it once, at INFO")
    void agentAbsenceIsAnnouncedOnceAtInfo() {
        ConcurrencyRunner.AGENT_ABSENCE_LOGGED.set(false);

        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(NarratedDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(NarratedDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        List<ILoggingEvent> announcements = appender.list.stream()
            .filter(e -> e.getFormattedMessage().startsWith("runner.agent.absent"))
            .toList();
        assertEquals(1, announcements.size(),
            "the agent's absence is a JVM-global fact: exactly one announcement across "
                + "any number of runs, or a large suite drowns in repetition. Got: " + events());
        ILoggingEvent announcement = announcements.get(0);
        assertSame(Level.INFO, announcement.getLevel(),
            "INFO, not DEBUG: the user who never attached the agent is exactly the user "
                + "who will not have DEBUG enabled");
        String message = announcement.getFormattedMessage();
        assertTrue(message.contains("test="),
            "the event names the test that triggered it: " + message);
        assertTrue(message.contains("async-test-agent"),
            "the hint names the artifact that closes the gap: " + message);
    }

    @Test
    @DisplayName("a daemon-hygiene run on virtual threads says the detector cannot see anything")
    void daemonHygieneInertnessIsAnnouncedOnceAtInfo() {
        ConcurrencyRunner.DAEMON_HYGIENE_INERT_LOGGED.set(false);

        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(NarratedDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(NarratedDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        List<ILoggingEvent> announcements = appender.list.stream()
            .filter(e -> e.getFormattedMessage().startsWith("runner.detector.inert"))
            .filter(e -> e.getFormattedMessage().contains("detector=DaemonThreadHygieneDetector"))
            .toList();
        assertEquals(1, announcements.size(),
            "once per JVM, like runner.agent.absent: a suite of a thousand @AsyncTest methods "
                + "must not repeat it. Filtered by detector because more than one detector can "
                + "be inert in the same run. Got: " + events());
        ILoggingEvent announcement = announcements.get(0);
        assertSame(Level.INFO, announcement.getLevel(),
            "INFO, not DEBUG: the user reading a clean daemon-hygiene report is exactly the "
                + "user who will not have DEBUG enabled");
        String message = announcement.getFormattedMessage();
        assertTrue(message.contains("detector=DaemonThreadHygieneDetector"),
            "the event names the detector that cannot observe anything: " + message);
        assertTrue(message.contains("test="),
            "the event names the test that triggered it: " + message);
        assertTrue(message.contains("inherit"),
            "the reason names the mechanism, because that is what tells the reader it applies "
                + "to their code as well: " + message);
        assertTrue(message.contains("Executors.defaultThreadFactory"),
            "the hint names a thread this detector can still judge; an announcement that only "
                + "says 'cannot see' leaves the user with no next step: " + message);
    }

    @Test
    @DisplayName("the same run on platform threads says it too: the workers are daemon in both modes")
    void platformThreadRunsAlsoAnnounceTheDetectorIsInert() {
        ConcurrencyRunner.DAEMON_HYGIENE_INERT_LOGGED.set(false);

        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(PlatformThreadDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        List<ILoggingEvent> announcements = appender.list.stream()
            .filter(e -> e.getFormattedMessage().startsWith("runner.detector.inert"))
            .filter(e -> e.getFormattedMessage().contains("detector=DaemonThreadHygieneDetector"))
            .toList();
        assertEquals(1, announcements.size(),
            "useVirtualThreads = false used to be the documented way to make this detector work, "
                + "and stopped being one when the runner's platform workers became daemon threads "
                + "so that a deadlocked worker could not hold the JVM open (#479). A thread the "
                + "body creates inherits that flag in either mode, so the announcement belongs in "
                + "both. Got: " + events());
        assertSame(Level.INFO, announcements.get(0).getLevel(),
            "INFO, not DEBUG: the user reading a clean daemon-hygiene report is exactly the "
                + "user who will not have DEBUG enabled");
        assertTrue(announcements.get(0).getFormattedMessage().contains("workers are daemon"),
            "the reason names the runner's own workers, not the thread mode, because switching "
                + "the mode no longer changes it: " + announcements.get(0).getFormattedMessage());
    }

    @Test
    @DisplayName("a deadlock-detecting run on virtual threads says the detector cannot see the workers")

    void deadlockInertnessIsAnnouncedOnceAtInfo() {
        ConcurrencyRunner.DEADLOCK_INERT_LOGGED.set(false);

        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(NarratedDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(NarratedDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        List<ILoggingEvent> announcements = appender.list.stream()
            .filter(e -> e.getFormattedMessage().startsWith("runner.detector.inert"))
            .filter(e -> e.getFormattedMessage().contains("detector=DeadlockDetector"))
            .toList();

        // Conditional on purpose, and the condition is the contract. Since issue #367 the
        // detector reads virtual-thread cycles out of the JVM's own thread dump, on JDKs whose
        // dump names monitors. Announcing inertness there would be false; not announcing it where
        // the dump is thin would be the silent green. So the assertion is the equivalence, which
        // holds on every JDK, rather than a fixed answer that would only hold on some.
        if (DeadlockDetector.canSeeVirtualThreadDeadlocks()) {
            assertTrue(announcements.isEmpty(),
                "this JVM can read a virtual-thread deadlock out of its own thread dump, so "
                    + "calling the detector inert would be false. Got: " + events());
            return;
        }
        assertEquals(1, announcements.size(),
            "once per JVM: a suite of a thousand @AsyncTest methods must not repeat it. Got: "
                + events());
        ILoggingEvent announcement = announcements.get(0);
        assertSame(Level.INFO, announcement.getLevel(),
            "INFO, not DEBUG: a clean deadlock report is the most reassuring output this "
                + "library produces, and the user reading one will not have DEBUG enabled");
        String message = announcement.getFormattedMessage();
        assertTrue(message.contains("test="),
            "the event names the test that triggered it: " + message);
        assertTrue(message.contains("findDeadlockedThreads"),
            "the reason names the JMX call that cannot see virtual threads: " + message);
        assertTrue(message.contains("useVirtualThreads"),
            "the hint names the setting that makes the detector able to see: " + message);
    }

    @Test
    @DisplayName("the same run on platform threads makes no claim about the deadlock detector")
    void platformThreadRunsDoNotClaimTheDeadlockDetectorIsInert() {
        ConcurrencyRunner.DEADLOCK_INERT_LOGGED.set(false);

        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(PlatformThreadDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        assertTrue(events().stream().noneMatch(m -> m.contains("detector=DeadlockDetector")),
            "on platform threads findDeadlockedThreads() can close a cycle between the "
                + "workers, so claiming the detector is inert would be false. Got: " + events());
    }

    @Test
    @DisplayName("a livelock-detecting run on virtual threads says the detector cannot see the workers")
    void livelockInertnessIsAnnouncedOnceAtInfo() {
        ConcurrencyRunner.LIVELOCK_INERT_LOGGED.set(false);

        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(NarratedDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(NarratedDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        List<ILoggingEvent> announcements = appender.list.stream()
            .filter(e -> e.getFormattedMessage().startsWith("runner.detector.inert"))
            .filter(e -> e.getFormattedMessage().contains("detector=LivelockDetector"))
            .toList();
        assertEquals(1, announcements.size(),
            "once per JVM, like the other two. Got: " + events());
        ILoggingEvent announcement = announcements.get(0);
        assertSame(Level.INFO, announcement.getLevel(),
            "INFO, not DEBUG: detectAll turns this detector on, so the user affected is anyone "
                + "who has not opted out, and they will not have DEBUG enabled");
        String message = announcement.getFormattedMessage();
        assertTrue(message.contains("dumpAllThreads"),
            "the reason names the JMX call that cannot see virtual threads: " + message);
    }

    @Test
    @DisplayName("the same run on platform threads makes no claim about the livelock detector")
    void platformThreadRunsDoNotClaimTheLivelockDetectorIsInert() {
        ConcurrencyRunner.LIVELOCK_INERT_LOGGED.set(false);

        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(PlatformThreadDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        assertTrue(events().stream().noneMatch(m -> m.contains("detector=LivelockDetector")),
            "on platform threads the workers are in the dump, so the detector can see them and "
                + "claiming otherwise would be false. Got: " + events());
    }

    @Test
    @DisplayName("a detector note that is not a finding reaches a passing run's log once, at INFO")
    void aNoteThatIsNotAFindingIsLoggedOnceAtInfo() {
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(UndecidedLockDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        List<ILoggingEvent> notes = appender.list.stream()
            .filter(e -> e.getFormattedMessage().startsWith("runner.detector.note"))
            .toList();
        assertEquals(1, notes.size(),
            "the undecided slot is one note, and the run analyses once, so one event however many "
                + "workers and rounds recorded it (#816). Got: " + events());
        ILoggingEvent note = notes.get(0);
        assertSame(Level.INFO, note.getLevel(),
            "INFO, not WARN: a note is not a finding, and not DEBUG: the user whose recording the "
                + "note asks to change is not running with DEBUG on");
        String message = note.getFormattedMessage();
        assertTrue(message.contains("test=undecided"), "the event names the test: " + message);
        assertTrue(message.contains("detector=SynchronizedNonFinalDetector"),
            "the event names the detector that wrote the note: " + message);
        assertTrue(message.contains("notes=1"), "and how many notes it wrote: " + message);
        assertTrue(message.contains("recordLockObject(lock, \\\"lock\\\", Holder.class, this)"),
            "the note itself, quotes escaped, with the call that decides the slot: " + message);
    }

    @Test
    @DisplayName("a detector with more notes than the cap logs the cap, and the total")
    void notesAreCappedPerDetector() {
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(ManyUndecidedLocksDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        List<String> notes = events().stream().filter(m -> m.startsWith("runner.detector.note")).toList();
        assertEquals(ConcurrencyRunner.NOTE_EVENTS_PER_DETECTOR, notes.size(),
            "five undecided slots, but this is somebody else's build log: the cap, not five. Got: "
                + events());
        assertTrue(notes.stream().allMatch(m -> m.contains("notes=5")),
            "each line says how many there were, so the reader knows some were left out: " + notes);
    }

    @Test
    @DisplayName("a run whose detectors wrote no note logs no note event")
    void aRunWithoutNotesLogsNoNoteEvent() {
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(NarratedDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        assertTrue(events().stream().noneMatch(m -> m.startsWith("runner.detector.note")),
            "every detector enabled and nothing noted, so nothing new in somebody else's build "
                + "log. Got: " + events());
    }

    @Test
    @DisplayName("a note in a report that has a finding stays in the report and is not logged again")
    void aNoteBesideAFindingStaysInTheReport() {
        Throwable failure = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(ReassignedStaticLockDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.failed(1))
            .failed().list().get(0)
            .getPayload(org.junit.platform.engine.TestExecutionResult.class).orElseThrow()
            .getThrowable().orElseThrow();

        assertTrue(failure.getMessage().contains("SynchronizedNonFinalDetector"),
            "the reassigned static lock is a finding and fails the gate as before: " + failure);
        assertTrue(events().stream().noneMatch(m -> m.startsWith("runner.detector.note")),
            "the report that carries the finding prints the note with it, so a log event would "
                + "say it twice. Got: " + events());
    }

    /** Runs under the extension so the narrative is produced by the real code path. */
    static class NarratedDummy {
        private final AtomicInteger counter = new AtomicInteger();

        @AsyncTest(threads = 3, invocations = 2)
        void narrated() {
            counter.incrementAndGet();
        }
    }

    /** The same run with the virtual-thread executor turned off. */
    static class PlatformThreadDummy {
        private final AtomicInteger counter = new AtomicInteger();

        @AsyncTest(threads = 2, invocations = 1, useVirtualThreads = false)
        void onPlatformThreads() {
            counter.incrementAndGet();
        }
    }

    /** A lock of one's own per worker, in a non-final instance field, recorded without the instance. */
    static final class Holder {
        private Object lock = new Object();
        private int count;
    }

    /** Several monitors on one slot recorded without an owner: undecided, a note and no finding. */
    static class UndecidedLockDummy {
        @AsyncTest(threads = 3, invocations = 2, includes = DetectorType.SYNCHRONIZED_NON_FINAL)
        void undecided() {
            Holder holder = new Holder();
            AsyncTestContext.synchronizedNonFinalDetector().recordLockObject(holder.lock, "lock", Holder.class);
            synchronized (holder.lock) {
                holder.count++;
            }
        }
    }

    /** Five undecided slots in one run, more than the per-detector cap. */
    static class ManyUndecidedLocksDummy {
        @AsyncTest(threads = 2, invocations = 1, includes = DetectorType.SYNCHRONIZED_NON_FINAL)
        void many() {
            Holder holder = new Holder();
            for (int slot = 0; slot < 5; slot++) {
                AsyncTestContext.synchronizedNonFinalDetector()
                    .recordLockObject(holder.lock, "lock" + slot, Holder.class);
            }
        }
    }

    /** The same undecided slot beside a reassigned static lock, which is a finding. */
    static class ReassignedStaticLockDummy {
        private static Object lock = new Object();

        @AsyncTest(threads = 3,
                invocations = 2,
                failOn = FailOn.LOW,
                includes = DetectorType.SYNCHRONIZED_NON_FINAL)
        void reassigned() {
            Object mine = new Object();
            lock = mine;
            AsyncTestContext.synchronizedNonFinalDetector()
                .recordLockObject(mine, "lock", ReassignedStaticLockDummy.class);
            Holder holder = new Holder();
            AsyncTestContext.synchronizedNonFinalDetector().recordLockObject(holder.lock, "lock", Holder.class);
        }
    }

    /** The single note event a passing run of {@code dummy} logs for {@code detector}. */
    private String theOneNoteOf(Class<?> dummy, String detector) {
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(dummy))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        List<ILoggingEvent> notes = appender.list.stream()
            .filter(e -> e.getFormattedMessage().startsWith("runner.detector.note"))
            .toList();
        assertEquals(1, notes.size(),
            "one note, logged once per run however many workers and rounds recorded it (#816). Got: "
                + events());
        assertSame(Level.INFO, notes.get(0).getLevel(), "a note is not a finding, so INFO, never WARN");
        String message = notes.get(0).getFormattedMessage();
        assertTrue(message.contains("detector=" + detector + " notes=1 note=\""),
            "the event names the detector and the one note it wrote: " + message);
        return message;
    }

    @Test
    @DisplayName("a condition registered without its lock and left waiting is noted in a passing run")
    void aConditionRegisteredWithoutItsLockIsNoted() {
        String message = theOneNoteOf(LockLessConditionDummy.class, "ConditionVariableDetector");
        assertTrue(message.contains("registered without its lock")
                && message.contains("registerCondition(lock, condition, ready, name)"),
            "the note names the registration that lets the lock confirm the waits: " + message);
    }

    @Test
    @DisplayName("an exchange end recorded with no start is noted in a passing run")
    void anExchangeEndWithNoStartIsNoted() {
        String message = theOneNoteOf(EndWithoutStartDummy.class, "ExchangerDetector");
        assertTrue(message.contains("note=\"swap: 4 end(s)")
                && message.contains("record the start and the end on the calling thread"),
            "the note names the exchanger, counts the ends and says how to record them: " + message);
    }

    @Test
    @DisplayName("waits recorded with no lock named are noted once in a passing run")
    void lockLessStarvationWaitsAreNotedOnce() {
        String message = theOneNoteOf(LockLessStarvationDummy.class, "ReentrantLockDetector");
        assertTrue(message.contains("note=\"4 wait(s)")
                && message.contains("recordStarvation(lock, threadName, waitTimeMs)"),
            "four recorded waits are one note that counts them and names the overload that judges "
                + "them, not four lines: " + message);
    }

    /** A condition registered without its lock; each worker records an await it then skips. */
    static class LockLessConditionDummy {
        static final ReentrantLock LOCK = new ReentrantLock();
        static final Condition READY = LOCK.newCondition();

        @AsyncTest(threads = 2,
                invocations = 2,
                useVirtualThreads = true,
                includes = DetectorType.CONDITION_VARIABLES)
        void lockLess() {
            ConditionVariableDetector monitor = AsyncTestContext.conditionVariableDetector();
            monitor.registerCondition(READY, "ready");
            monitor.recordAwait(READY, "ready"); // recorded before the predicate check, which held
        }
    }

    /** Pairs of workers exchange and record the completion, but never the start. */
    static class EndWithoutStartDummy {
        static final Exchanger<String> EXCHANGER = new Exchanger<>();

        @AsyncTest(threads = 2, invocations = 2, includes = DetectorType.EXCHANGER)
        void endOnly() throws Exception {
            String received = EXCHANGER.exchange("mine", 10, TimeUnit.SECONDS);
            AsyncTestContext.exchangerDetector().recordExchangeComplete(EXCHANGER, "swap", received);
        }
    }

    /** Each worker times its wait for a lock and records it with no lock named. */
    static class LockLessStarvationDummy {
        static final ReentrantLock LOCK = new ReentrantLock();

        @AsyncTest(threads = 2, invocations = 2, includes = DetectorType.REENTRANT_LOCK)
        void timed() {
            long start = System.nanoTime();
            LOCK.lock();
            try {
                long waitedMs = Math.max(1, (System.nanoTime() - start) / 1_000_000);
                AsyncTestContext.reentrantLockDetector().recordStarvation(Thread.currentThread().getName(), waitedMs);
            } finally {
                LOCK.unlock();
            }
        }
    }
}
