package se.deversity.asynctest.agent;

import com.example.agentfixture.HookedPoolBean;
import com.example.agentfixture.HookedPoolBean.HookedPool;
import com.example.agentfixture.HookedPoolBean.KeepingHandler;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A {@code ThreadPoolExecutor} subclass that overrides {@code beforeExecute} and
 * {@code afterExecute}, with a rejection handler of its own, through the real weaver (#1173).
 * Before, such a pool's tasks were left unwrapped, since each override would have been handed the
 * wrapper; the agent now rewrites the overrides and the handler to be handed the caller's task, so
 * the pool is wrapped for like a plain one, and a woven {@code getQueue} shows the caller's tasks.
 *
 * <p>Own class, because {@code selfAttach} is at most once per JVM; {@code forkEvery=1} gives it
 * its own JVM.
 */
@Tag("e2e")
class PoolTaskOverrideWeavingTest {

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
    @DisplayName("a pool with woven overrides and handler is wrapped for, and each sees the caller's task")
    void overridesAndHandlerSeeTheCallersTask() throws InterruptedException {
        HookedPoolBean bean = new HookedPoolBean();
        KeepingHandler handler = new KeepingHandler();
        HookedPool pool = new HookedPool(handler);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ran = new CountDownLatch(1);
        Runnable blocker = () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Runnable queued = ran::countDown;
        Runnable refused = () -> { };
        AsyncTestContext.install(new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build()));
        try {
            bean.execute(pool, blocker);
            bean.execute(pool, queued);
            bean.execute(pool, refused);

            assertNotSame(queued, pool.getQueue().peek(),
                    "the pool's own queue holds the wrapper that orders the task after its execute;"
                            + " before #1173 a pool with these overrides was left unwrapped");
            assertEquals(List.of(queued), bean.queued(pool), "woven code reads the caller's task");
            assertEquals(List.of(refused), handler.rejected, "the woven handler is handed the task itself");
        } finally {
            release.countDown();
            AsyncTestContext.uninstall();
        }
        assertTrue(ran.await(10, TimeUnit.SECONDS), "the queued task never ran");
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(List.of(blocker, queued), pool.before, "beforeExecute is handed each task itself");
        assertEquals(List.of(blocker, queued), pool.after, "afterExecute is handed each task itself");
    }
}
