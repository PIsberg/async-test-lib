package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A detector's conclusion must not depend on whether two threads raced to register the instance
 * they are sharing.
 *
 * <p><strong>Why this exists.</strong> Five detectors auto-registered per-instance state with a
 * check-then-act:
 *
 * <pre>{@code
 * State s = states.get(id);
 * if (s == null) { s = new State(...); states.put(id, s); }   // lost update
 * }</pre>
 *
 * <p>Two threads touching an instance for the first time both saw {@code null}, both built a
 * state, and the second {@code put} discarded the first. Each thread then accumulated into its
 * own object while only one remained reachable from the map, so the surviving state recorded a
 * single thread and the "more than one thread touched this" test never tripped. The detectors
 * went silent under exactly the contention they exist to find — a false negative, the direction
 * that matters, and one no single-threaded unit test can see.
 *
 * <p>The property asserted here is stronger and cheaper to maintain than a per-detector count:
 * run the same two recordings twice, once with the threads released together and once strictly
 * one after the other, and require the same verdict. Racing registration is the only difference
 * between the runs, so a disagreement is the lost update and nothing else.
 *
 * <p>The second assertion is the anti-vacuity guard. Equality alone would pass trivially if the
 * serialized run also reported nothing, which would mean the scenario never exercised the
 * registration path at all — so the serialized run is required to fire. A failure there is a
 * fixture problem, not a detector problem, and the message says so.
 */
@DisplayName("Registration must be atomic: racing and serialized runs reach the same verdict")
class DetectorRegistrationRaceTest {

    /** One scenario: a per-thread recording action, plus the detector's verdict. */
    private record Scenario(Runnable record, BooleanSupplier hasIssues) { }

    @Test
    @DisplayName("no detector's verdict changes when two threads race to auto-register")
    void verdictIsIndependentOfRegistrationRacing() {
        List<String> divergent = new ArrayList<>();
        List<String> vacuous = new ArrayList<>();

        scenarios().forEach((name, factory) -> {
            Scenario racing = factory.get();
            onTwoThreads(racing.record(), true);
            boolean racedVerdict = racing.hasIssues().getAsBoolean();

            Scenario serial = factory.get();
            onTwoThreads(serial.record(), false);
            boolean serialVerdict = serial.hasIssues().getAsBoolean();

            if (!serialVerdict) {
                vacuous.add(name);
            } else if (!racedVerdict) {
                divergent.add(name);
            }
        });

        assertTrue(vacuous.isEmpty(),
                "These scenarios reported nothing even with the two threads run strictly one "
                        + "after the other:\n  " + String.join("\n  ", vacuous)
                        + "\n\nThat makes the comparison below vacuous - both runs would agree "
                        + "on 'no finding' whether or not registration is atomic. This is a "
                        + "fixture defect: strengthen the scenario until the serialized run "
                        + "fires, then the racing run has something to disagree with.");

        assertTrue(divergent.isEmpty(),
                "These detectors reported a finding when two threads recorded one after the "
                        + "other, and reported nothing when the same two recordings raced:\n  "
                        + String.join("\n  ", divergent)
                        + "\n\nThe only difference between the runs is whether the threads "
                        + "collided while auto-registering the shared instance, so the finding "
                        + "was lost in registration. Look for `s = map.get(id); if (s == null) "
                        + "{ s = new State(); map.put(id, s); }` on the record path and replace "
                        + "it with map.computeIfAbsent(...). Losing the finding under contention "
                        + "is the worst possible direction for a concurrency detector: the "
                        + "harder the user's test hammers the bug, the more likely the detector "
                        + "is to say nothing is wrong.");
    }

    /**
     * The same lost update in {@code LockLeakDetector}, which runs the other way.
     *
     * <p>{@code recordLockAcquired} was already atomic, with a comment explaining why;
     * {@code recordLockReleased} was not. A release is only ever the auto-registering call when
     * the lock was taken before the detector was installed, and a release dropped in the race
     * leaves {@code acquireCount} above {@code releaseCount} — so the racy run reports a leak
     * against code that balanced every acquire. That is a false positive on correct locking,
     * which is the failure most likely to make a team switch the detector off.
     */
    @Test
    @DisplayName("a release lost to a registration race does not invent a lock leak")
    void lostReleaseDoesNotInventALockLeak() {
        LockLeakDetector detector = new LockLeakDetector();
        Lock shared = new ReentrantLock();

        // Release first - that is the only call that auto-registers, since recordLockAcquired
        // has used computeIfAbsent for some time. Then acquire, and stop. Every thread released
        // as many times as it acquired, so acquireCount must not end up above releaseCount.
        //
        // The release must be the *last* racing call for this to bite: an extra release
        // afterwards would push the count back up and hide the dropped one, which is exactly
        // how the first version of this test passed against the unfixed detector.
        Runnable balanced = () -> {
            detector.recordLockReleased(shared, "sharedLock");
            detector.recordLockAcquired(shared, "sharedLock");
        };
        onTwoThreads(balanced, true);

        LockLeakDetector.LockLeakReport report = detector.analyze();

        // hasIssues() is true either way here - the lock is still held at analysis time, which
        // is a legitimate separate finding - so this asserts on the leak line specifically, in
        // the report text the user actually reads.
        assertFalse(report.toString().contains("potential leak"),
                "Each thread released once and acquired once, so acquires never exceed "
                        + "releases and there is no leak to report. A leak line here means a "
                        + "release was lost while the two threads raced to auto-register the "
                        + "lock - the detector inventing a leak in correct code, which is the "
                        + "failure most likely to get it switched off. Report:\n" + report);
    }

    /**
     * Runs {@code body} on two threads, either released together or strictly sequenced.
     *
     * @param together {@code true} to release both from a barrier so their first-touch
     *                 registration collides; {@code false} to run the second only after the
     *                 first has finished
     */
    private static void onTwoThreads(Runnable body, boolean together) {
        try {
            if (!together) {
                Thread first = new Thread(body, "registration-serial-1");
                first.start();
                first.join();
                Thread second = new Thread(body, "registration-serial-2");
                second.start();
                second.join();
                return;
            }
            CyclicBarrier barrier = new CyclicBarrier(2);
            Runnable sync = () -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                body.run();
            };
            Thread t1 = new Thread(sync, "registration-race-1");
            Thread t2 = new Thread(sync, "registration-race-2");
            t1.start();
            t2.start();
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** The detectors whose record path auto-registers per-instance state on first touch. */
    private static Map<String, Supplier<Scenario>> scenarios() {
        Map<String, Supplier<Scenario>> cases = new LinkedHashMap<>();

        cases.put("SharedRandomDetector", () -> {
            SharedRandomDetector d = new SharedRandomDetector();
            Random shared = new Random();
            return new Scenario(() -> d.recordRandomAccess(shared, "rng", "nextInt"),
                    () -> d.analyze().hasIssues());
        });
        cases.put("SimpleDateFormatDetector", () -> {
            SimpleDateFormatDetector d = new SimpleDateFormatDetector();
            SimpleDateFormat shared = new SimpleDateFormat("yyyy-MM-dd");
            return new Scenario(() -> {
                shared.format(new Date());
                d.recordFormat(shared, "isoDate");
            }, () -> d.analyze().hasIssues());
        });
        cases.put("CacheConcurrencyDetector", () -> {
            CacheConcurrencyDetector d = new CacheConcurrencyDetector();
            Map<String, String> shared = new HashMap<>();
            return new Scenario(() -> {
                d.recordGet(shared, "userCache", "k");
                d.recordPut(shared, "userCache", "k", "v");
            }, () -> d.analyze().hasIssues());
        });
        // LockLeakDetector is deliberately absent: its lost update runs the other way. A
        // dropped release makes acquires exceed releases, so the racy run invents a leak
        // instead of hiding one. lostReleaseDoesNotInventALockLeak() covers that direction.
        cases.put("SemaphoreMisuseDetector", () -> {
            SemaphoreMisuseDetector d = new SemaphoreMisuseDetector();
            Semaphore shared = new Semaphore(1);
            return new Scenario(() -> d.recordAcquire(shared, "permits"),
                    () -> d.analyze().hasIssues());
        });

        return cases;
    }
}
