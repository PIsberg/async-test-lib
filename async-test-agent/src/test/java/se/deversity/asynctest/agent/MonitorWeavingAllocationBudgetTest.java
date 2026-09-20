package se.deversity.asynctest.agent;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AgentConcurrencyUtilHooks;
import se.deversity.asynctest.AgentMonitorHooks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The load-time half of the performance contract: what the monitor wrapper's buffering pass
 * costs on a class that never waits (#711).
 *
 * <p><strong>Why a ratio.</strong> Since #707 the monitor table cannot emit a method as it
 * arrives: a loop around a wait may sit in a method declared before the one that waits, so every
 * method is buffered as replayable actions and played back at {@code visitEnd}. Every other
 * substitution table streams. That makes the cost measurable without a stopwatch and without a
 * machine-dependent byte count: weave the same classes twice, once with the streaming
 * concurrency table and once with the buffering monitor table, and divide. Absolute allocation
 * moves with the JDK, the ASM version and the subject list; the ratio between two passes over
 * the same bytes in the same JVM does not.
 *
 * <p><strong>Why it is not covered elsewhere.</strong> {@code RunnerAllocationBudgetTest} gates
 * 80,000 bytes per <em>body execution</em>, and weaving happens once per class before any body
 * runs, so nothing there can see this. {@code MonitorWeavingIsTransparentTest} pins that the
 * buffering pass changes no bytecode, which is a correctness claim, not a cost one.
 *
 * <p><strong>What was tried instead.</strong> The obvious reduction is to hold the methods in
 * ASM's own encoding rather than as captured lambdas: write each one into a scratch
 * {@code ClassWriter} as it arrives and read that back at {@code visitEnd}. Built and measured, it
 * came to 2.14x against the tape's 1.95x on the same six classes in the same JVM, because the
 * second {@code ClassReader} rebuilds the whole constant pool as strings. It is not in the tree;
 * this line is here so nobody spends the afternoon again.
 *
 * <p><strong>How the ceiling was set.</strong> Red first: the ceiling was 1.0, the failure
 * message printed the measured ratio, and the ceiling was set above the reading with room for
 * JIT and JDK variation but not for a shape change. When it fails, the message carries the fresh
 * measurement; re-derive the ceiling the same way and say so in the commit, do not just raise it
 * to the number that made the run green. The numbers this gate was set from are recorded in
 * {@code docs/agent/attaching.md}.
 */
class MonitorWeavingAllocationBudgetTest {

    /**
     * Classes to weave, read off this module's own test classpath.
     *
     * <p>Chosen to look like what {@code collections=true} actually meets: mostly large, ordinary,
     * loop-heavy classes that never call {@code wait} and therefore pay the buffering pass for
     * nothing, plus one fixture that does wait so the measured pass is not a degenerate one. ASM's
     * own writer classes are the largest bodies reachable here without adding a dependency, and
     * they are the shape the corpus agent-pairs lane weaves out of Guava and Jackson: long
     * methods, many branches, no monitors.
     */
    private static final List<String> SUBJECTS = List.of(
            "net.bytebuddy.jar.asm.ClassReader",
            "net.bytebuddy.jar.asm.ClassWriter",
            "net.bytebuddy.jar.asm.Frame",
            "net.bytebuddy.jar.asm.SymbolTable",
            "net.bytebuddy.pool.TypePool$Default",
            "com.example.agentfixture.DoWhileWaitHandOffBean");

    /**
     * Allocated bytes for one buffering weave over {@link #SUBJECTS}, as a multiple of one
     * streaming weave over the same classes.
     *
     * <p>Measured 2026-09-20 after a warmup pass: 3,218,824 bytes against 1,648,720 over 119,871
     * class-file bytes, which is 1.95x. Three runs on JDK 26 and one on JDK 21, Windows 11, all
     * four identical to the byte, because allocation counting is exact once the passes are warm
     * and nothing here is escape-analysed away differently between the two. The same two passes
     * took 13-14 ms against 9-11 ms on JDK 26 and 9 ms against 8 ms on JDK 21.
     *
     * <p>2.2 is 1.13x the reading. It is a shape gate, not a percent gate: holding each action in
     * a {@code LinkedList} node instead of an array slot measures 2.08x, and the scratch-class
     * variant the class javadoc describes measures 2.14x; both are meant to pass, because neither
     * is a cost anyone would notice. What fails it is the kind of change that holds a whole extra
     * object per instruction: a {@code CopyOnWriteArrayList} in place of the {@code ArrayList},
     * which copies the tape on every append, measures 21.00x.
     */
    private static final double CEILING_RATIO = 2.2d;

    /** Keeps the writer's output from being optimised away. */
    private static long sink;

    private static List<Subject> subjects = List.of();

    private static TypePool typePool = TypePool.Empty.INSTANCE;

    @BeforeAll
    static void readTheSubjects() {
        ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(
                MonitorWeavingAllocationBudgetTest.class.getClassLoader());
        typePool = TypePool.Default.of(locator);
        List<Subject> read = new ArrayList<>();
        for (String name : SUBJECTS) {
            byte[] bytes;
            try {
                ClassFileLocator.Resolution resolution = locator.locate(name);
                assertTrue(resolution.isResolved(),
                        name + " is not on this module's test classpath. The subject list is the "
                                + "measurement: replacing an entry changes the number this gate "
                                + "was derived from, so fix the classpath rather than the list.");
                bytes = resolution.resolve();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            read.add(new Subject(typePool.describe(name).resolve(), bytes));
        }
        subjects = List.copyOf(read);
    }

    @Test
    @DisplayName("buffering every method of a class that never waits stays within its budget")
    void theBufferingPassStaysWithinItsBudget() {
        var mx = ManagementFactory.getThreadMXBean();
        assumeTrue(mx instanceof com.sun.management.ThreadMXBean,
                "needs the HotSpot ThreadMXBean for per-thread allocation counters");
        var bean = (com.sun.management.ThreadMXBean) mx;
        assumeTrue(bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled(),
                "thread allocation accounting is off on this JVM");

        AsmVisitorWrapper streaming = CollectionAccessWeaver
                .concurrencySubstitutions(AgentConcurrencyUtilHooks.class).get(0);
        AsmVisitorWrapper buffering = CollectionAccessWeaver
                .monitorSubstitutions(AgentMonitorHooks.class).get(0);

        // Warmup: class loading, the type pool's first resolutions and JIT are one-time costs a
        // consumer pays once, not per woven class. Both passes are warmed so neither is measured
        // cold against the other's warm run.
        weave(streaming);
        weave(buffering);

        long id = Thread.currentThread().threadId();
        long beforeStreaming = bean.getThreadAllocatedBytes(id);
        long streamingStarted = System.nanoTime();
        weave(streaming);
        long streamingNanos = System.nanoTime() - streamingStarted;
        long streamingBytes = bean.getThreadAllocatedBytes(id) - beforeStreaming;

        long beforeBuffering = bean.getThreadAllocatedBytes(id);
        long bufferingStarted = System.nanoTime();
        weave(buffering);
        long bufferingNanos = System.nanoTime() - bufferingStarted;
        long bufferingBytes = bean.getThreadAllocatedBytes(id) - beforeBuffering;

        long classFileBytes = 0L;
        for (Subject subject : subjects) {
            classFileBytes += subject.bytes().length;
        }
        double ratio = (double) bufferingBytes / streamingBytes;

        assertTrue(ratio <= CEILING_RATIO,
                "Weaving " + subjects.size() + " classes (" + classFileBytes + " class-file bytes) "
                        + "with the buffering monitor table allocated " + bufferingBytes
                        + " bytes against " + streamingBytes + " for the streaming table, a ratio "
                        + "of " + String.format(Locale.ROOT, "%.2f", ratio) + "x, above the ceiling "
                        + "of " + CEILING_RATIO + "x. The same two passes took "
                        + bufferingNanos / 1_000_000L + " ms and " + streamingNanos / 1_000_000L
                        + " ms, which is not asserted on but is what says whether the ratio "
                        + "matters. Either the buffering pass started holding more per "
                        + "instruction, or the ceiling needs re-deriving (measure, then record the "
                        + "reading in docs/agent/attaching.md; see the class javadoc).");
    }

    /** Runs one substitution table over every subject, through a real reader and writer. */
    private static void weave(AsmVisitorWrapper wrapper) {
        for (Subject subject : subjects) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = wrapper.wrap(subject.type(), writer, null, typePool, null, null,
                    ClassWriter.COMPUTE_MAXS, 0);
            new ClassReader(subject.bytes()).accept(visitor, 0);
            sink += writer.toByteArray().length;
        }
    }

    /** One class to weave: its description, which the wrapper needs, and its bytes. */
    private record Subject(TypeDescription type, byte[] bytes) {
    }
}
