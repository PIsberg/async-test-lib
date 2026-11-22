package se.deversity.asynctest.agent;

import com.example.agentfixture.GuardedHandOffBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.telemetry.TelemetryBridge;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An object handed over through a field read and written under one monitor (#1247), end to end:
 * weaver, hooks, happens-before model and {@code AtomicityValidator}.
 *
 * <p>A reference stored under a lock and loaded under the same lock, returning that object, is an
 * edge: the reader has the object only because the store put it there. Each case that orders has
 * a twin that differs by the one thing the edge needs, and must keep its finding: the load under
 * no lock, the load under another lock, and a write of the object after the hand-off.
 *
 * <p>Own class: {@code selfAttach} is at most once per JVM and this needs {@code fields=true}.
 */
@Tag("e2e")
class GuardedHandOffWeavingTest {

    @BeforeAll
    static void attachWithFieldWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,fields=true");
    }

    @AfterEach
    void stopRegistry() {
        TelemetryRegistry.stop();
    }

    @Test
    @DisplayName("an object taken under the monitor it was stored under is ordered after the store")
    void aGuardedHandOffOrdersTheReceiversUse() throws InterruptedException {
        GuardedHandOffBean bean = new GuardedHandOffBean();
        int[] read = new int[1];
        List<String> findings = findings(bean::handOff, () -> read[0] = bean.bumpTakenUnderTheLock());

        assertEquals(2, read[0], "the reader took the mail and updated it, or nothing was measured");
        assertFalse(findings.stream().anyMatch(f -> f.contains("body")),
                "the reader loaded mail under the lock the writer stored it under and got that "
                        + "object, so its update of body is ordered after the writer's. Findings "
                        + "were: " + findings);
    }

    @Test
    @DisplayName("the same hand-off through synchronized methods of the holder orders the use")
    void aHandOffThroughSynchronizedMethodsOrders() throws InterruptedException {
        GuardedHandOffBean bean = new GuardedHandOffBean();
        int[] read = new int[1];
        List<String> findings = findings(bean::handOffInASynchronizedMethod,
                () -> read[0] = bean.bumpTakenInASynchronizedMethod());

        assertEquals(2, read[0], "the reader took the mail and updated it, or nothing was measured");
        assertFalse(findings.stream().anyMatch(f -> f.contains("body")),
                "both methods hold the bean's own monitor, so the store and the load share a "
                        + "lock. Findings were: " + findings);
    }

    @Test
    @DisplayName("the same object taken with no monitor held keeps its finding")
    void anUnguardedLoadOrdersNothing() throws InterruptedException {
        GuardedHandOffBean bean = new GuardedHandOffBean();
        int[] read = new int[1];
        List<String> findings = findings(bean::handOff, () -> read[0] = bean.bumpTakenWithoutTheLock());

        assertEquals(2, read[0], "the reader took the mail and updated it, or nothing was measured");
        assertTrue(findings.stream().anyMatch(f -> f.contains("body")),
                "a load under no lock is the racy read of mail itself and orders nothing. "
                        + "Findings were: " + findings);
    }

    @Test
    @DisplayName("the same object taken under another monitor keeps its finding")
    void aLoadUnderAnotherLockOrdersNothing() throws InterruptedException {
        GuardedHandOffBean bean = new GuardedHandOffBean();
        int[] read = new int[1];
        List<String> findings = findings(bean::handOff, () -> read[0] = bean.bumpTakenUnderAnotherLock());

        assertEquals(2, read[0], "the reader took the mail and updated it, or nothing was measured");
        assertTrue(findings.stream().anyMatch(f -> f.contains("body")),
                "the writer never held otherLock, so the load under it excludes nothing. "
                        + "Findings were: " + findings);
    }

    @Test
    @DisplayName("a write of the object after the hand-off keeps its finding")
    void aWriteAfterTheHandOffIsStillARace() throws InterruptedException {
        GuardedHandOffBean bean = new GuardedHandOffBean();
        int[] read = new int[1];
        List<String> findings = findings(bean::handOffThenWrite,
                () -> read[0] = bean.bumpTakenUnderTheLock());

        assertEquals(3, read[0], "the reader took the mail after both writes, or nothing was measured");
        assertTrue(findings.stream().anyMatch(f -> f.contains("body")),
                "the writer's second update of body comes after its release, so the reader "
                        + "acquired nothing that orders it. Findings were: " + findings);
    }

    /** Runs the writer to completion, then the reader, on two workers the model does not order. */
    private static List<String> findings(Runnable writer, Runnable reader) throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Set<Long> workers = ConcurrentHashMap.newKeySet();
        try (TelemetryBridge bridge = TelemetryBridge.activateWithFilter(validator, workers::contains)) {
            CountDownLatch written = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            new Thread(() -> {
                workers.add(Thread.currentThread().threadId());
                try {
                    writer.run();
                } finally {
                    written.countDown();
                    done.countDown();
                }
            }, "handoff-writer").start();
            new Thread(() -> {
                workers.add(Thread.currentThread().threadId());
                try {
                    assertTrue(written.await(10, TimeUnit.SECONDS), "the writer never finished");
                    reader.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "handoff-reader").start();
            assertTrue(done.await(10, TimeUnit.SECONDS), "worker threads did not finish");
            TelemetryRegistry.flush();
        }
        return validator.analyzeAtomicity().unsafeFieldAccesses.stream().toList();
    }
}
