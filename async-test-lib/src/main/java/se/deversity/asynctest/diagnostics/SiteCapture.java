package se.deversity.asynctest.diagnostics;

import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.annotations.AIPublicAPI;

import java.lang.StackWalker.StackFrame;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Captures the first non-framework stack frame for a detector access event so
 * violation reports can point users at the exact source line that produced the
 * issue — instead of just naming threads.
 *
 * <p>Without source attribution, a report like
 * {@code "'sha256' accessed from 2 threads (T1, T2)"} forces the user to grep
 * their codebase for {@code MessageDigest} usage and guess. With attribution,
 * the same report can carry {@code "at MyService.encrypt(MyService.java:42)"},
 * turning a 5-minute hunt into a click.
 *
 * <p>A single {@link StackWalker#walk} call returning at most the first matching
 * frame, which still allocates over 1,100 bytes. Detectors gate the call behind a
 * "first access" check, so the cost is paid once per key rather than per access:
 * per (instance, thread) in {@code SharedMessageDigestDetector}, per field in
 * {@code RaceConditionDetector}. Which call site a key keeps is the first one; a
 * site the key's later accesses reach is not seen (#849).
 */
@AIPerformance(constraint = "Called from detector recordAccess paths; do not allocate when a site is already captured for a given key.")
public final class SiteCapture {

    private static final StackWalker WALKER =
            StackWalker.getInstance(Set.of(), 8);

    /**
     * Packages / classnames whose frames are skipped when looking for the user
     * caller. Deliberately narrower than "everything under se.deversity.asynctest":
     * we keep frames in that package that belong to user test classes (which
     * happens to be where our own fixtures live too).
     */
    private static final String[] FRAMEWORK_PREFIXES = {
            "se.deversity.asynctest.runner.",
            "se.deversity.asynctest.extension.",
            "se.deversity.asynctest.benchmark.",
            "se.deversity.common.license.",
            "java.lang.reflect.",
            "jdk.internal.reflect.",
            "sun.reflect.",
            "jdk.proxy",
            "java.util.concurrent.",
            "java.util.ArrayList",
            "java.lang.Thread",
            "org.junit.",
            "org.gradle.",
            "worker.org.gradle.",
    };

    /** Class-name suffixes flagged as framework detector/validator/monitor code. */
    private static final String[] FRAMEWORK_SUFFIXES = {
            "Detector",
            "Monitor",
            "Validator",
            "SiteCapture",
    };

    /**
     * The package and name prefix of the library's agent hooks, {@code AgentSharedInstanceHooks}
     * and its siblings. A woven call site reaches a detector through one of them, so without this
     * rule the report named the hook's line instead of the user's (#853).
     */
    private static final String AGENT_HOOKS_PREFIX = "se.deversity.asynctest.Agent";

    /** Name suffix that, with {@link #AGENT_HOOKS_PREFIX}, marks a hook rather than a test of one. */
    private static final String AGENT_HOOKS_SUFFIX = "Hooks";

    private SiteCapture() {}

    /**
     * Returns the first stack frame outside the framework / JDK reflection /
     * JUnit, or {@link Optional#empty()} if none could be identified (rare).
     *
     * @return the first stack frame in user code, or empty when none could be attributed
     */
    public static Optional<Site> capture() {
        return WALKER.walk(stream -> stream
                .filter(SiteCapture::isUserFrame)
                .findFirst()
                .map(Site::of));
    }

    private static boolean isUserFrame(StackFrame f) {
        return !isFrameworkClass(f.getClassName());
    }

    /**
     * Whether a frame in {@code cls} is skipped when looking for the user caller.
     *
     * @param cls the frame's binary class name, as {@link StackFrame#getClassName()} gives it
     * @return true when the frame belongs to the library, the JDK or a test framework
     */
    static boolean isFrameworkClass(String cls) {
        for (String prefix : FRAMEWORK_PREFIXES) {
            if (cls.startsWith(prefix)) return true;
        }
        if (isAgentHook(cls)) return true;
        // Detectors live in the diagnostics package and have a known set of
        // class-name suffixes. We must not surface a detector's own frame as
        // the "user site" of an access it recorded.
        int lastDot = cls.lastIndexOf('.');
        String simple = lastDot < 0 ? cls : cls.substring(lastDot + 1);
        for (String suffix : FRAMEWORK_SUFFIXES) {
            if (simple.endsWith(suffix)) return true;
        }
        return false;
    }

    /**
     * A top-level {@code Agent*Hooks} class of the library's root package, or a class nested in
     * one. The prefix alone would be wider than the hooks: this package also holds the library's
     * own tests ({@code AgentSharedInstanceHooksTest}), whose frames are the caller a site test
     * expects. Allocates nothing, since it runs once per frame of every walk.
     */
    private static boolean isAgentHook(String cls) {
        int start = AGENT_HOOKS_PREFIX.length();
        if (!cls.startsWith(AGENT_HOOKS_PREFIX) || cls.indexOf('.', start) >= 0) {
            return false;
        }
        int nested = cls.indexOf('$', start);
        int end = nested < 0 ? cls.length() : nested;
        return cls.startsWith(AGENT_HOOKS_SUFFIX, end - AGENT_HOOKS_SUFFIX.length());
    }

    /**
     * Immutable record of a captured caller frame. Equality is by (class, line) so
     * a {@code Set<Site>} natively deduplicates repeat accesses from the same line.
     *
     * @param className  fully-qualified name of the class the frame is in
     * @param methodName name of the method the frame is in
     * @param fileName   source file the frame came from
     * @param lineNumber line within {@code fileName}, and part of the equality contract
     */
    @AIPublicAPI
    @AIImmutable(note = "Java record — fields are final by language; types are all primitives or String.")
    public record Site(String className, String methodName, String fileName, int lineNumber) {

        /**
         * Compact constructor.
         *
         * @throws NullPointerException if className or methodName is null
         */
        public Site {
            Objects.requireNonNull(className);
            Objects.requireNonNull(methodName);
        }

        static Site of(StackFrame f) {
            return new Site(f.getClassName(), f.getMethodName(),
                    f.getFileName() != null ? f.getFileName() : "Unknown",
                    f.getLineNumber());
        }

        /**
         * Human-readable {@code Class.method(File.java:42)} form.
         *
         * @return the site rendered as {@code Class.method(File:line)} for the report
         */
        public String render() {
            String shortClass = className.contains(".")
                    ? className.substring(className.lastIndexOf('.') + 1)
                    : className;
            return shortClass + "." + methodName + "(" + fileName + ":" + lineNumber + ")";
        }
    }
}
