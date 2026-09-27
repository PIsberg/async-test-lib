package se.deversity.asynctest.agent;

import com.example.agentfixture.DaemonDecisionShapesBean;
import com.example.unwovenfixture.UnwovenThreadMaker;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.report.Violation;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Which lingering threads DaemonThreadHygieneDetector reports once the agent weaves threads, for
 * the daemon decisions a woven {@code setDaemon} does not make (#737).
 *
 * <p>Every thread here is started from a runner worker, which is daemon, so every one of them is
 * daemon by inheritance unless something decided otherwise. A report must therefore come from what
 * the agent saw, never from the flag: a decision on a {@code Thread.Builder} is a decision, and a
 * thread constructed where nothing is woven cannot be judged undecided, because a decision there
 * would have been invisible.
 */
@Tag("e2e")
class DaemonDecisionWeavingTest {

    private static AsyncFindings findings;
    private static final CountDownLatch RELEASE = new CountDownLatch(1);
    private static final List<Thread> STARTED = new CopyOnWriteArrayList<>();

    private final DaemonDecisionShapesBean starter = new DaemonDecisionShapesBean();

    @BeforeAll
    static void attachWithThreadWeaving() {
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
        findings = AsyncFindings.collect();
    }

    @AsyncTest(threads = 1, invocations = 1, detectDaemonThreadHygiene = true)
    void startingEveryShapeInsideRun() {
        Runnable lingering = () -> {
            try {
                RELEASE.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        STARTED.add(starter.builtDaemonThenStarted("built-daemon", lingering));
        STARTED.add(starter.builderDaemonStart("builder-start-daemon", lingering));
        STARTED.add(starter.builtUndecidedThenStarted("built-undecided", lingering));
        STARTED.add(starter.builderUndecidedStart("builder-start-undecided", lingering));
        STARTED.add(starter.subclassStarted("subclass-undecided", lingering));
        STARTED.add(starter.start(UnwovenThreadMaker.daemon("unwoven-daemon", lingering)));
        STARTED.add(starter.start(UnwovenThreadMaker.undecided("unwoven-undecided", lingering)));
        STARTED.add(starter.start(UnwovenThreadMaker.nonDaemon("unwoven-non-daemon", lingering)));
    }

    @AfterAll
    static void onlyTheUndecidedAndTheNonDaemonAreReported() {
        RELEASE.countDown();
        for (Thread t : STARTED) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            Set<String> reported = new TreeSet<>();
            for (Violation v : findings.violations()) {
                if (!v.detector().contains("DaemonThreadHygiene")) {
                    continue;
                }
                // The listener gets one violation per detector, with every finding in its report.
                for (Thread t : STARTED) {
                    if (v.toString().contains("thread name='" + t.getName() + "'")) {
                        reported.add(t.getName());
                    }
                }
            }
            // Reported: a builder with no daemon decision, whether it started the thread or a
            // separate start() did, and a subclass that decides nothing, because the agent saw each
            // constructed and nothing decide; and a non-daemon thread, whoever made it.
            // Silent: daemon(true) on a builder, which is the decision the rule asks for, and both
            // threads made where nothing is woven and daemon when started. The undecided one of
            // those two is a real miss, and it is the price of never reporting the decided one.
            assertEquals(new TreeSet<>(Set.of("built-undecided", "builder-start-undecided",
                            "subclass-undecided", "unwoven-non-daemon")), reported,
                    "Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
