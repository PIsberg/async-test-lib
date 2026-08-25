package se.deversity.asynctest.agent;

import com.example.agentfixture.AfterAttachBean;
import com.example.agentfixture.BeforeAttachBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The check that makes a missed class say so.
 *
 * <p>Issue #321 was expensive because it was invisible: Byte Buddy calls a listener for a type it
 * wove, one it ignored and one it failed on, and calls nothing at all for one it never saw, so a
 * class woven by nothing left no line in any log. {@link AttachCoverageReport} closes that by
 * diffing what the transformer was handed against what the JVM has loaded. These tests pin both
 * directions of the diff, and - just as importantly - pin what it must stay quiet about, because
 * a diagnostic that cries wolf on every bootstrap class is one people learn to ignore.
 */
@Tag("e2e")
class AttachCoverageReportTest {

    private static final ElementMatcher<TypeDescription> IGNORE = AsyncTestAgent.ignoreMatcher();
    private static final ElementMatcher<TypeDescription> ANY = AsyncTestAgent.typeMatcher(List.of());

    /** Narrows the loaded-class list so the diff is over a stated set, not the whole test JVM. */
    private static Instrumentation loadedClassesAre(List<Class<?>> loaded) {
        Instrumentation real;
        try {
            real = ByteBuddyAgent.install();
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            real = null;
        }
        assumeTrue(real != null,
                "needs an Instrumentation handle (run with -Djdk.attach.allowAttachSelf=true)");
        // The poisoned class is not in the list, so nothing is refused: this double is used here
        // only for its narrowed getAllLoadedClasses(), and isModifiableClass still answers for
        // real, which is what makes the filtering assertions meaningful.
        return new PoisoningInstrumentation(real, Void.class, loaded);
    }

    @Test
    void aLoadedClassTheTransformerNeverSawIsNamed() {
        Instrumentation inst = loadedClassesAre(List.of(BeforeAttachBean.class, AfterAttachBean.class));

        List<String> missed = AttachCoverageReport.unconsulted(
                inst, Set.of(BeforeAttachBean.class.getName()), IGNORE, ANY);

        assertEquals(List.of(AfterAttachBean.class.getName()), missed,
                "the class the transformer was never handed is the whole point of the check");
    }

    @Test
    void aClassTheTransformerSawIsNotNamed() {
        Instrumentation inst = loadedClassesAre(List.of(BeforeAttachBean.class, AfterAttachBean.class));

        List<String> missed = AttachCoverageReport.unconsulted(
                inst,
                Set.of(BeforeAttachBean.class.getName(), AfterAttachBean.class.getName()),
                IGNORE, ANY);

        assertTrue(missed.isEmpty(), "a consulted class is covered however the agent then ruled on "
                + "it; being ignored is an answer, not an absence. Got: " + missed);
    }

    @Test
    void aClassTheIgnoreMatcherExcludesIsNotNamed() {
        // This library's own package is ignored by the install, so it was never a candidate and
        // its absence from the consulted set says nothing.
        Instrumentation inst = loadedClassesAre(List.of(AsyncTestAgent.class, AfterAttachBean.class));

        List<String> missed = AttachCoverageReport.unconsulted(inst, Set.of(), IGNORE, ANY);

        assertEquals(List.of(AfterAttachBean.class.getName()), missed,
                "reporting a class the agent deliberately skips would make the check noise; "
                        + "the same matcher the install used has to decide that");
    }

    @Test
    void aClassOutsideTheIncludesIsNotNamed() {
        Instrumentation inst = loadedClassesAre(List.of(BeforeAttachBean.class));
        ElementMatcher<TypeDescription> onlySomewhereElse =
                AsyncTestAgent.typeMatcher(List.of("com.example.somewhereelse"));

        List<String> missed = AttachCoverageReport.unconsulted(inst, Set.of(), IGNORE, onlySomewhereElse);

        assertTrue(missed.isEmpty(),
                "includes= is the user's own statement of what to leave alone, so a class outside "
                        + "it is not a miss. Got: " + missed);
    }

    @Test
    void nothingIsPrintedWhenNothingIsMissing() {
        assertEquals("", capturedErrFrom(List.of()),
                "silence is the expected outcome of a healthy attach, and a line per attach saying "
                        + "so would be noise in somebody else's build log");
    }

    @Test
    void everyMissedClassIsNamedWithItsConsequence() {
        String printed = capturedErrFrom(List.of("com.example.One", "com.example.Two"));

        assertTrue(printed.contains("com.example.One") && printed.contains("com.example.Two"),
                "the report has to name the classes; a count alone is the same dead end as the "
                        + "silence it replaces. Got: " + printed);
        assertTrue(printed.contains("invisible to every agent-fed detector"),
                "the reader is someone whose detectors went quiet, so the line must say what the "
                        + "consequence is, not only how many. Got: " + printed);
        assertFalse(printed.contains("more (debug=true prints all)"),
                "two names are under the cap and must not be summarised. Got: " + printed);
    }

    @Test
    void aFloodIsSummarisedRatherThanPrintedInFull() {
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            many.add("com.example.Type" + i);
        }

        String printed = capturedErrFrom(many);

        assertTrue(printed.contains("... and 30 more (debug=true prints all)"),
                "50 names past a cap of 20 must summarise the remaining 30 rather than fill the "
                        + "build log. Got: " + printed);
    }

    /** {@return whatever the report wrote to {@code System.err} for {@code missed}} */
    private static String capturedErrFrom(List<String> missed) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            AttachCoverageReport.report(missed, false);
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
