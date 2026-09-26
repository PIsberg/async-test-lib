package se.deversity.asynctest.agent;

import com.example.agentfixture.PlainDequePoolBean;
import com.example.agentfixture.PlainDequePoolBean.Item;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.diagnostics.SharedCollectionDetector;
import se.deversity.asynctest.telemetry.TelemetryBridge;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An object pool over a plain {@code ArrayDeque}, through woven code (#751).
 *
 * <p>The deque orders nothing itself, so a poll out of it hands the item to one thread only while
 * the pool's lock serialises it. The agent sees a {@code synchronized} block's monitor through the
 * woven instruction, and a {@code synchronized} method's, which comes from the access flag, because
 * the weaver hands it to the queue hooks (#796). These cases pin what each shape amounts to end to
 * end, with the item used unlocked
 * between a give-back and the next borrow on two threads, in the order the drain sees it.
 *
 * <p>Separate class because {@code selfAttach} is at-most-once per JVM and this class needs
 * {@code fields=true,collections=true}; {@code reuseForks=false} gives it its own fork.
 */
@Tag("e2e")
class PlainDequePoolWeavingTest {

    @BeforeAll
    static void attachWithFieldAndCollectionWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");
        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,fields=true,collections=true");
    }

    @AfterEach
    void stopRegistry() {
        TelemetryRegistry.stop();
    }

    @Test
    void aPoolGuardedBySynchronizedMethodsStaysSilent() throws Exception {
        assertFalse(reports(PlainDequePoolBean::giveBack, PlainDequePoolBean::borrow),
                "giveBack and borrow are synchronized methods, so the pool's monitor serialises the "
                        + "deque and the item leaves it to one thread at a time. The weaver hands "
                        + "that monitor to the queue hooks, and both sides share it (#751, #796)");
    }

    @Test
    void aPoolGuardedBySynchronizedBlocksStaysSilent() throws Exception {
        assertFalse(reports(PlainDequePoolBean::giveBackInBlock, PlainDequePoolBean::borrowInBlock),
                "offer and poll under the same visible monitor are the synchronized pool (#751)");
    }

    @Test
    void aPoolGivingBackInAMethodAndBorrowingInABlockStaysSilent() throws Exception {
        assertFalse(reports(PlainDequePoolBean::giveBack, PlainDequePoolBean::borrowInBlock),
                "both sides hold the pool's monitor, the give-back through the synchronized flag, "
                        + "which the weaver passes to the hook, and the borrow through a woven "
                        + "block; the two sets share it (#751, #796)");
    }

    @Test
    void aGiveBackAndABorrowUnderTwoDifferentLocksFires() throws Exception {
        assertTrue(reports(PlainDequePoolBean::giveBackInBlock,
                        PlainDequePoolBean::borrowUnderAnotherLock),
                "the give-back held the pool's monitor and the borrow a lock the give-backs never "
                        + "take, so nothing serialised the poll against the offer and the item's two "
                        + "users race (#751)");
    }

    @Test
    void aSynchronizedMethodGiveBackAndABorrowUnderAnotherLockFires() throws Exception {
        assertTrue(reports(PlainDequePoolBean::giveBack, PlainDequePoolBean::borrowUnderAnotherLock),
                "the give-back holds the pool's monitor through the synchronized flag and the borrow "
                        + "holds a lock the give-backs never take. The weaver passes the method's "
                        + "monitor to the queue hook, so both sides show a lock and they share none "
                        + "(#796)");
    }

    @Test
    void aPoolGuardedByStaticSynchronizedMethodsStaysSilentAndItsBrokenTwinFires() throws Exception {
        assertFalse(reports((pool, item) -> PlainDequePoolBean.giveBackToTheSharedPool(item),
                        pool -> PlainDequePoolBean.borrowFromTheSharedPool()),
                "both sides hold the class through static synchronized methods (#796)");
        assertTrue(reports((pool, item) -> PlainDequePoolBean.giveBackToTheSharedPool(item),
                        PlainDequePoolBean::borrowFromTheSharedPoolUnderAnotherLock),
                "the give-back holds the class and the borrow a lock the give-backs never take (#796)");
    }

    @Test
    void aGuardedGiveBackWithAnUnguardedBorrowIsLeftToSharedCollectionDetector() throws Exception {
        assertFalse(reports(PlainDequePoolBean::giveBackInBlock, PlainDequePoolBean::borrowUnguarded),
                "one visible side proves nothing to AtomicityValidator: an unguarded borrow cannot "
                        + "be told from one inside a synchronized method (#751)");
        PlainDequePoolBean pool = new PlainDequePoolBean();
        AsyncTestContext context = new AsyncTestContext(
                AsyncTestConfig.builder().detectSharedCollections(true).build());
        SharedCollectionDetector[] detector = new SharedCollectionDetector[1];
        try (Actors actors = new Actors()) {
            Item item = PlainDequePoolBean.newItem();
            actors.run(1, () -> withContext(context, () -> {
                detector[0] = AsyncTestContext.sharedCollectionDetector();
                pool.giveBackInBlock(item);
            }));
            actors.run(2, () -> withContext(context, () -> PlainDequePoolBean.use(pool.borrowUnguarded())));
        }
        assertTrue(detector[0].analyze().hasIssues(),
                "the deque itself was written by two threads, one of them holding no lock, which "
                        + "is what SharedCollectionDetector reports; that is where this shape is "
                        + "caught while the ownership edge stays");
    }

    /** Runs {@code body} with {@code context} installed on the calling thread. */
    private static Void withContext(AsyncTestContext context, Runnable body) {
        AsyncTestContext.install(context);
        try {
            body.run();
        } finally {
            AsyncTestContext.uninstall();
        }
        return null;
    }

    /**
     * Actor 1 uses a fresh item and gives it back, actor 2 borrows it and uses it, both uses
     * unlocked. {@return whether AtomicityValidator reported the item's field}
     */
    private static boolean reports(BiConsumer<PlainDequePoolBean, Item> giveBack,
                                   Function<PlainDequePoolBean, Item> borrow) throws Exception {
        PlainDequePoolBean pool = new PlainDequePoolBean();
        AtomicityValidator validator = new AtomicityValidator();
        try (Actors actors = new Actors(); TelemetryBridge bridge =
                TelemetryBridge.activateWithFilter(validator, actors.ids::contains)) {
            validator.markInvocationStart();
            Item item = PlainDequePoolBean.newItem();
            actors.run(1, () -> {
                PlainDequePoolBean.use(item);
                giveBack.accept(pool, item);
                return null;
            });
            actors.run(2, () -> {
                PlainDequePoolBean.use(borrow.apply(pool));
                return null;
            });
            TelemetryRegistry.flush();
        }
        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();
        return report.unsafeFieldAccesses.stream().anyMatch(line -> line.contains("uses"));
    }

    /** Numbered actors, each one thread for the life of a case, run one step at a time. */
    private static final class Actors implements AutoCloseable {
        final Set<Long> ids = ConcurrentHashMap.newKeySet();
        private final java.util.Map<Integer, ExecutorService> threads = new java.util.HashMap<>();

        <T> T run(int actor, Callable<T> step) throws Exception {
            ExecutorService thread = threads.computeIfAbsent(actor,
                    ignored -> Executors.newSingleThreadExecutor(r -> new Thread(r, "actor-" + actor)));
            return thread.submit(() -> {
                ids.add(Thread.currentThread().threadId());
                return step.call();
            }).get(10, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            threads.values().forEach(ExecutorService::shutdownNow);
        }
    }
}
