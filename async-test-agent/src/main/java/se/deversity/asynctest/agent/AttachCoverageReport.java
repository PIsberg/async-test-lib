package se.deversity.asynctest.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;
import org.jspecify.annotations.Nullable;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Names the loaded classes a dynamic attach never consulted, instead of losing them in silence.
 *
 * <p>The failure this exists for is silent by construction. A class the transformer is never
 * asked about produces no line anywhere: not an {@code Instrumented} line, not a
 * {@code Failed to instrument} line, not a {@code Could not re-weave} line. Byte Buddy calls a
 * listener for a type it wove, for one it ignored and for one it failed on, and there is no
 * callback at all for a type it was never handed. So the only way to see the gap is to compare
 * what the transformer saw against what the JVM has loaded, which is what this does.
 *
 * <p>That gap was real: issue #321 cost {@code corpus-eval} 14 of its 20 detections, and it took
 * an afternoon of probes to find because every log in the run looked healthy. The concrete cause
 * is fixed - the install now discovers with
 * {@code RedefinitionStrategy.DiscoveryStrategy.Reiterating} - and this check is the part that
 * survives the next one. It turns "my detectors went quiet" into a named list.
 *
 * <p>Only the dynamic-attach path runs it. Under {@code premain} there is nothing to compare:
 * classes are woven as they load, and the set of classes loaded before the transformer exists is
 * empty by definition.
 *
 * @since 1.10.0
 */
final class AttachCoverageReport {

    /**
     * How many names to print before summarising the rest.
     *
     * <p>This runs inside somebody else's test suite, so its output is somebody else's build log.
     * A handful of names is a diagnosis; a thousand is a denial of service against the log, and
     * the count alone already says which of the two happened.
     */
    private static final int MAX_NAMES = 20;

    private AttachCoverageReport() {
    }

    /**
     * Records every type name the transformer was handed, whatever it then decided about it.
     *
     * <p>{@code onDiscovery} fires before the ignore matcher and before any transformation, so a
     * name recorded here means "the transformer was consulted", which is the exact question this
     * report asks. The set is written from every class-loading thread, hence the concurrent set.
     */
    static final class Discovery extends AgentBuilder.Listener.Adapter {

        private final Set<String> consulted = ConcurrentHashMap.newKeySet();

        @Override
        public void onDiscovery(String typeName, @Nullable ClassLoader classLoader,
                                @Nullable JavaModule module, boolean loaded) {
            consulted.add(typeName);
        }

        /** {@return the type names the transformer was handed} */
        Set<String> consulted() {
            return consulted;
        }
    }

    /**
     * {@return the loaded classes that were candidates for weaving but were never consulted}
     *
     * <p>A class is only reported when the agent would have woven it had it been asked, so every
     * expected absence is filtered out first rather than explained afterwards: the JVM refuses to
     * retransform some classes, bootstrap-loaded types are ignored by the install, and the
     * {@code includes}/{@code excludes} matchers are the user's own statement of what to leave
     * alone. What is left is a class the agent meant to weave and did not.
     *
     * @param inst        the instrumentation handle the agent installed on
     * @param consulted   the type names the transformer was handed
     * @param typeIgnore  the ignore matcher the install used
     * @param typeMatcher the positive {@code includes} matcher the install used
     */
    static List<String> unconsulted(Instrumentation inst,
                                    Set<String> consulted,
                                    ElementMatcher<? super TypeDescription> typeIgnore,
                                    ElementMatcher<? super TypeDescription> typeMatcher) {
        List<String> missed = new ArrayList<>();
        for (Class<?> type : inst.getAllLoadedClasses()) {
            if (type == null || type.isArray() || type.isPrimitive()) {
                continue;
            }
            // Bootstrap-loaded types are excluded by the install's own class-loader matcher, and
            // a class the JVM will not modify was never a candidate in the first place.
            if (type.getClassLoader() == null || !inst.isModifiableClass(type)) {
                continue;
            }
            if (consulted.contains(type.getName())) {
                continue;
            }
            // The real matchers, not a second copy of their rules: a reimplementation here would
            // be a twin that drifts, and would report exactly the classes the agent skipped on
            // purpose. Both only read the name and the modifiers, so describing the type costs
            // no eager resolution of its signatures - which is the very thing that opened the
            // gap this report exists to catch.
            TypeDescription described;
            try {
                described = TypeDescription.ForLoadedType.of(type);
            } catch (Throwable t) { // NOPMD - a type we cannot describe is not a finding
                continue;
            }
            if (typeIgnore.matches(described) || !typeMatcher.matches(described)) {
                continue;
            }
            missed.add(type.getName());
        }
        missed.sort(null);
        return missed;
    }

    /**
     * Prints the report, and prints nothing when there is nothing to say.
     *
     * <p>Silence here is the expected outcome, so the message is written for someone who has
     * never heard of this check and is reading it in a CI log: it says what the consequence is,
     * that those classes are invisible to the detectors, rather than only what the number is.
     *
     * @param missed the classes that were never consulted
     * @param debug  {@code true} to print every name rather than the first 20
     */
    static void report(List<String> missed, boolean debug) {
        if (missed.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder()
                .append("[ASYNC-TEST-AGENT] ").append(missed.size())
                .append(" already-loaded class(es) were never handed to the transformer, so they "
                        + "are woven by nothing and invisible to every agent-fed detector. This "
                        + "is a gap in the attach, not a property of the code under test; "
                        + "attaching with -javaagent at JVM startup avoids it entirely:");
        int shown = debug ? missed.size() : Math.min(MAX_NAMES, missed.size());
        for (int i = 0; i < shown; i++) {
            message.append("\n[ASYNC-TEST-AGENT]   ").append(missed.get(i));
        }
        if (shown < missed.size()) {
            message.append("\n[ASYNC-TEST-AGENT]   ... and ").append(missed.size() - shown)
                    .append(" more (debug=true prints all)");
        }
        System.err.println(message);
    }
}
