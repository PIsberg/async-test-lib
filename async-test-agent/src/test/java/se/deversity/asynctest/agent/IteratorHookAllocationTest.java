package se.deversity.asynctest.agent;

import com.example.agentfixture.IteratorWalk;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What the woven {@code Iterator} hooks cost a for-each inside a run (#1309).
 *
 * <p>Every for-each in a woven class compiles to {@code hasNext} and {@code next}, so these hooks
 * sit on the hottest call path the agent rewrites. The detector keeps one state per iterator, so a
 * loop pays once for its iterator and nothing per element; this pins both, measured on the
 * walking thread with an all-detector context installed, as a runner worker has. Measured on
 * Windows 11, JDK 27, two runs: 0.04 bytes per element and 1,174 bytes per walk of eight
 * elements, of which the {@code ArrayList} iterator itself is 32; the rest is the detector's state
 * for that iterator, kept weakly. The 2,048-byte bound leaves room for a JDK whose collections
 * are laid out larger, and trips on a second state object per iterator.
 *
 * <p>Own class: {@code selfAttach} is at most once per JVM and this needs {@code collections=true}.
 */
@Tag("e2e")
class IteratorHookAllocationTest {

    private static final int SHORT = 8;
    private static final int LONG = 4_096;
    private static final int WALKS = 2_000;

    @BeforeAll
    static void attachWithCollectionWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,collections=true");
    }

    @Test
    @DisplayName("a woven for-each pays per iterator, not per element")
    void aWovenForEachAllocatesPerIteratorNotPerElement() {
        List<Integer> shortList = new ArrayList<>(Collections.nCopies(SHORT, 1));
        List<Integer> longList = new ArrayList<>(Collections.nCopies(LONG, 1));
        AsyncTestContext context = new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build());
        AsyncTestContext.install(context);
        try {
            for (int i = 0; i < WALKS; i++) {
                assertEquals(SHORT, IteratorWalk.sum(shortList));
                assertEquals(LONG, IteratorWalk.sum(longList));
            }
            long perShortWalk = bytesPerWalk(shortList);
            long perLongWalk = bytesPerWalk(longList);
            double perElement = (perLongWalk - perShortWalk) / (double) (LONG - SHORT);
            assertTrue(perElement < 1.0,
                    "each element of a woven for-each allocated " + perElement + " bytes (a walk of "
                            + SHORT + " allocated " + perShortWalk + ", of " + LONG + " " + perLongWalk
                            + "): the hooks must not allocate per hasNext or next");
            assertTrue(perShortWalk < 2_048,
                    "one woven for-each allocated " + perShortWalk + " bytes for its iterator: the"
                            + " detector's state per iterator has grown");
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("the hooks are live: one iterator stepped by two workers is reported")
    void theWovenHooksReachTheDetector() throws InterruptedException {
        AsyncTestContext context = new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build());
        java.util.Iterator<Integer> shared = new ArrayList<>(Collections.nCopies(64, 1)).iterator();
        Runnable step = () -> {
            AsyncTestContext.install(context);
            try {
                IteratorWalk.takeOne(shared);
            } finally {
                AsyncTestContext.uninstall();
            }
        };
        Thread first = new Thread(step, "iterator-a");
        first.start();
        first.join();
        Thread second = new Thread(step, "iterator-b");
        second.start();
        second.join();
        AsyncTestContext.install(context);
        try {
            assertTrue(AsyncTestContext.sharedIteratorDetector().analyze().hasIssues(),
                    "two workers stepped one iterator through the woven hasNext and next, so the"
                            + " measurement above measured the hooks and not an unwoven loop");
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    private static long bytesPerWalk(List<Integer> values) {
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long me = Thread.currentThread().threadId();
        long before = threads.getThreadAllocatedBytes(me);
        for (int i = 0; i < WALKS; i++) {
            IteratorWalk.sum(values);
        }
        return (threads.getThreadAllocatedBytes(me) - before) / WALKS;
    }
}
