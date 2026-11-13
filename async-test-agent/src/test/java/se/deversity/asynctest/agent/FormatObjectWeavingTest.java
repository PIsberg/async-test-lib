package se.deversity.asynctest.agent;

import com.example.agentfixture.SharedThroughFormatObjectBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.report.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A format shared across threads is reported when it is reached through the calls
 * {@code java.text.Format} declares, {@code format(Object)} and {@code parseObject(String)}, and a
 * shared {@code MessageFormat} is reported at all (#1319).
 *
 * <p>One body per call site and the findings kept per test, so a finding is credited to the one
 * call that produced it: a {@code DecimalFormat} reported for {@code format(Object)} reads the
 * same as one reported for {@code parseObject}. The sentence naming the format is in the
 * finding's report attribute; its message is the report's heading.
 */
@Tag("e2e")
class FormatObjectWeavingTest {

    private static AsyncFindings findings;
    private static final Map<String, List<Violation>> BY_TEST = new ConcurrentHashMap<>();
    private static int credited;

    private final SharedThroughFormatObjectBean shared = new SharedThroughFormatObjectBean();

    @BeforeAll
    static void attachWithSharedInstanceWeaving() {
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

    @AfterEach
    void creditFindingsToThisTest(TestInfo info) {
        List<Violation> all = findings.violations();
        BY_TEST.computeIfAbsent(info.getTestMethod().orElseThrow().getName(), k -> new ArrayList<>())
                .addAll(all.subList(credited, all.size()));
        credited = all.size();
    }

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void decimalFormatObject() {
        shared.formatDecimal();
    }

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void decimalHeldAsFormat() {
        shared.formatDecimalHeldAsFormat();
    }

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void decimalParseObject() {
        shared.parseDecimalObject();
    }

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void dateFormatObject() {
        shared.formatDateObject();
    }

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void dateParseObject() {
        shared.parseDateObject();
    }

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void messageFormat() {
        shared.formatMessage();
    }

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void messageParse() {
        shared.parseMessage();
    }

    @AfterAll
    static void eachCallSiteIsReported() {
        try {
            List<String> missed = new ArrayList<>();
            for (List<String> expected : List.of(
                    List.of("decimalFormatObject", "DecimalFormat", "java.text.DecimalFormat"),
                    List.of("decimalHeldAsFormat", "DecimalFormat", "java.text.DecimalFormat"),
                    List.of("decimalParseObject", "DecimalFormat", "java.text.DecimalFormat"),
                    List.of("dateFormatObject", "SimpleDateFormat", "SimpleDateFormat"),
                    List.of("dateParseObject", "SimpleDateFormat", "SimpleDateFormat"),
                    List.of("messageFormat", "DecimalFormat", "MessageFormat is not thread-safe"),
                    List.of("messageParse", "DecimalFormat", "MessageFormat is not thread-safe"))) {
                boolean reported = BY_TEST.getOrDefault(expected.get(0), List.of()).stream()
                        .anyMatch(v -> v.detector().contains(expected.get(1))
                                && (v.message() + v.attributes()).contains(expected.get(2)));
                if (!reported) {
                    missed.add(expected.get(0));
                }
            }
            assertTrue(missed.isEmpty(),
                    "Four threads shared one format through a call java.text.Format declares and "
                            + "nothing was reported for: " + missed + ". Check that "
                            + "CollectionAccessWeaver's SHARED_INSTANCE_ENTRIES lists the Format "
                            + "entries and that the hook records by the receiver's runtime type. "
                            + "Findings by test were: " + BY_TEST);
        } finally {
            findings.close();
        }
    }
}
