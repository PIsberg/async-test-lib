package se.deversity.asynctest.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.lang.instrument.Instrumentation;
import java.util.List;

/**
 * Java instrumentation agent that transparently injects field-access telemetry into
 * application classes without requiring manual {@code recordFieldAccess()} callbacks.
 *
 * <h2>Motivation</h2>
 * The baseline approach requires test authors to pollute production service code with
 * detector hooks (e.g. {@code recordFieldAccess("count", count)}). This:
 * <ul>
 *   <li>Increases cognitive overhead and is prone to omission</li>
 *   <li>Changes JIT profiling, potentially hiding or creating races</li>
 *   <li>Couples production code to the test framework</li>
 * </ul>
 *
 * <h2>Approach</h2>
 * Using <a href="https://bytebuddy.net">Byte Buddy</a>, this agent intercepts every
 * getter and setter at class-load time and inserts an {@link Advice @Advice} prologue
 * that routes to {@link TelemetryRegistry#recordAccess(long, String, boolean)}. The
 * read/write decision is bound at instrumentation time rather than recomputed on every
 * call: getters (matched by {@link ElementMatchers#isGetter()}) are woven with
 * {@link ReadAccessAdvice} and setters (matched by {@link ElementMatchers#isSetter()})
 * with {@link WriteAccessAdvice}. Each advice supplies a single
 * {@link Advice.Origin @Advice.Origin}-derived identifier — a compile-time constant
 * baked into the woven class's constant pool — and a hardcoded {@code isWrite} flag, so
 * the prologue performs no string concatenation and no allocation per intercepted call.
 * The intercepted classes themselves require no modification.
 *
 * <h2>Attachment</h2>
 * Add to the JVM launch command:
 * <pre>{@code -javaagent:async-test-lib-<version>.jar}</pre>
 * The library JAR is agent-capable: its MANIFEST includes {@code Premain-Class} and
 * {@code Can-Retransform-Classes: true}.
 *
 * <h2>Scope</h2>
 * Instrumentation candidates are filtered by the ignore matcher built in
 * {@link #ignoreMatcher()}, which excludes types by name prefix
 * ({@code java.}, {@code jdk.}, {@code sun.}, {@code com.sun.}, {@code net.bytebuddy.}
 * and this library's own {@code se.deversity.asynctest.} package) as well as
 * {@linkplain ElementMatchers#isSynthetic() synthetic} types (e.g. lambda classes).
 * In addition, {@code premain} ignores every type loaded by the
 * {@linkplain ElementMatchers#isBootstrapClassLoader() bootstrap class loader}. This
 * combined matcher restores the exclusions that Byte Buddy applies by default (its own
 * classes, synthetic types, bootstrap-loaded types), which a bare {@code ignore(...)}
 * call would otherwise replace, preventing recursive instrumentation of JDK internals
 * and Byte Buddy itself.
 *
 * <p>The candidate set can be narrowed and widened via {@code agentArgs} (see
 * {@link AgentOptions}): {@code includes=} restricts the positive match to the named
 * package prefixes, while {@code excludes=} appends extra prefixes to the ignore
 * matcher. With no arguments the agent instruments every non-ignored class, exactly as
 * before.
 *
 * @since 1.6.0
 */
public final class AsyncTestAgent {

    private AsyncTestAgent() {}

    /**
     * JVM agent entry point invoked before {@code main()}.
     *
     * <p>The {@code agentArgs} string is an optional list of {@code key=value} entries
     * (separated by {@code ,} or {@code ;}) parsed by {@link AgentOptions}:
     * <ul>
     *   <li>{@code includes=<prefix>[;<prefix>...]} — instrument only types whose name
     *       starts with one of the prefixes (narrows the positive match).</li>
     *   <li>{@code excludes=<prefix>[;<prefix>...]} — never instrument types whose name
     *       starts with one of the prefixes (appended to the ignore matcher).</li>
     *   <li>{@code debug=true} — enable verbose diagnostics: log every instrumented type
     *       and emit full stack traces for instrumentation errors (see
     *       {@link DiagnosticListener}).</li>
     * </ul>
     * Example:
     * <pre>{@code -javaagent:async-test-lib.jar=includes=com.myapp;excludes=com.myapp.dto}</pre>
     * Whitespace is trimmed, empty entries are skipped, and unknown keys are ignored.
     * A {@code null} or blank argument leaves the default behavior (instrument every
     * non-ignored class) unchanged. Parsing never throws — an exception thrown from
     * {@code premain} would abort JVM startup.
     *
     * @param agentArgs  optional {@code includes}/{@code excludes} configuration, or
     *                   {@code null}
     * @param inst       the instrumentation handle provided by the JVM
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst);
    }

    /**
     * Shared installation path used by {@code premain} (and, in a later revision,
     * dynamic self-attach). Parses {@code agentArgs}, builds the ignore/type matchers
     * from the resulting {@link AgentOptions}, and installs the weaving agent on
     * {@code inst}.
     *
     * @param agentArgs  the raw agent argument string, or {@code null}
     * @param inst       the instrumentation handle to install on
     */
    private static void install(String agentArgs, Instrumentation inst) {
        TelemetryRegistry.start();

        AgentOptions options = AgentOptions.parse(agentArgs);

        // Byte Buddy's AgentBuilder.ignore(...) REPLACES the built-in default ignore
        // matcher, so this call must re-establish every exclusion the default provided:
        // name-based prefixes and synthetic types (see ignoreMatcher()) plus every type
        // loaded by the bootstrap class loader. Any user-supplied excludes= prefixes are
        // folded into the same type-level matcher. A RawMatcher lambda composes these
        // with OR semantics (the two-argument ignore(typeMatcher, classLoaderMatcher)
        // overload would AND them, which is not what we want here).
        ElementMatcher<? super TypeDescription> typeIgnore = ignoreMatcher(options.excludes());
        ElementMatcher<? super ClassLoader> bootstrapIgnore = ElementMatchers.isBootstrapClassLoader();

        new AgentBuilder.Default()
                .with(new DiagnosticListener(options.debug()))
                .ignore((typeDescription, classLoader, module, classBeingRedefined, protectionDomain) ->
                        typeIgnore.matches(typeDescription) || bootstrapIgnore.matches(classLoader))
                .type(typeMatcher(options.includes()))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(ReadAccessAdvice.class)
                                            .on(ElementMatchers.isGetter()))
                               .visit(Advice.to(WriteAccessAdvice.class)
                                            .on(ElementMatchers.isSetter())))
                .installOn(inst);
    }

    /**
     * Builds the type-level ignore matcher used by {@link #premain} to keep
     * instrumentation off the JDK, Byte Buddy, this library, and synthetic types.
     *
     * <p>A type is ignored when its fully-qualified name starts with any of
     * {@code java.}, {@code jdk.}, {@code sun.}, {@code com.sun.},
     * {@code net.bytebuddy.} or {@code se.deversity.asynctest.}, or when the type is
     * {@linkplain ElementMatchers#isSynthetic() synthetic} (for example, a lambda
     * class). {@code premain} additionally ignores bootstrap-loaded types via a class
     * loader matcher; that check is class-loader-scoped and therefore lives at the call
     * site rather than in this type-only matcher.
     *
     * <p>Package-private so it can be unit-tested against {@code TypeDescription}
     * instances without a live {@link Instrumentation} handle.
     *
     * @return an ignore matcher over {@link TypeDescription}
     */
    static ElementMatcher.Junction<TypeDescription> ignoreMatcher() {
        return ElementMatchers.<TypeDescription>nameStartsWith("java.")
                .or(ElementMatchers.nameStartsWith("jdk."))
                .or(ElementMatchers.nameStartsWith("sun."))
                .or(ElementMatchers.nameStartsWith("com.sun."))
                .or(ElementMatchers.nameStartsWith("net.bytebuddy."))
                .or(ElementMatchers.nameStartsWith("se.deversity.asynctest."))
                .or(ElementMatchers.isSynthetic());
    }

    /**
     * Builds the type-level ignore matcher, extending the built-in {@link #ignoreMatcher()}
     * with any user-supplied {@code excludes=} name prefixes.
     *
     * <p>Each exclude prefix is OR-ed onto the base matcher via
     * {@link ElementMatchers#nameStartsWith(String)}, so a type is ignored when it is
     * caught by the built-in exclusions <em>or</em> by any exclude prefix. An empty list
     * yields exactly the built-in matcher.
     *
     * <p>Package-private so it can be unit-tested without a live {@link Instrumentation}
     * handle.
     *
     * @param excludes user-supplied name prefixes to also ignore (never {@code null})
     * @return an ignore matcher over {@link TypeDescription}
     * @since 1.7.0
     */
    static ElementMatcher.Junction<TypeDescription> ignoreMatcher(List<String> excludes) {
        ElementMatcher.Junction<TypeDescription> matcher = ignoreMatcher();
        for (String prefix : excludes) {
            matcher = matcher.or(ElementMatchers.nameStartsWith(prefix));
        }
        return matcher;
    }

    /**
     * Builds the positive type matcher passed to {@code AgentBuilder.type(...)} from the
     * user-supplied {@code includes=} name prefixes.
     *
     * <p>When {@code includes} is empty the matcher is {@link ElementMatchers#any()} —
     * every non-ignored type is a candidate, preserving the default behavior. Otherwise
     * the matcher is the OR of {@link ElementMatchers#nameStartsWith(String)} over each
     * prefix, so only types under one of those prefixes are instrumented.
     *
     * <p>Package-private so it can be unit-tested without a live {@link Instrumentation}
     * handle.
     *
     * @param includes user-supplied name prefixes to instrument (never {@code null})
     * @return a positive type matcher over {@link TypeDescription}
     * @since 1.7.0
     */
    static ElementMatcher.Junction<TypeDescription> typeMatcher(List<String> includes) {
        if (includes.isEmpty()) {
            return ElementMatchers.any();
        }
        ElementMatcher.Junction<TypeDescription> matcher = ElementMatchers.none();
        for (String prefix : includes) {
            matcher = matcher.or(ElementMatchers.nameStartsWith(prefix));
        }
        return matcher;
    }

    /**
     * Byte Buddy {@link AgentBuilder.Listener} that surfaces instrumentation outcomes
     * which would otherwise be swallowed silently.
     *
     * <p>By default (non-debug) it logs a single line to {@code System.err} for each
     * failed transformation, in the format
     * <pre>{@code [ASYNC-TEST-AGENT] Failed to instrument <typeName>: <throwable>}</pre>
     * with the throwable's {@code toString()} only — no stack trace — so that a class the
     * agent could not weave is visible without flooding CI logs.
     *
     * <p>When {@code debug} is {@code true} (via {@code agentArgs=debug=true}) it also
     * logs one line per successfully instrumented type
     * (<pre>{@code [ASYNC-TEST-AGENT] Instrumented <typeName>}</pre>) and appends a full
     * stack trace after each error line.
     *
     * <p>Package-private and constructed with an explicit {@code debug} flag so its
     * behavior can be unit-tested by invoking {@link #onError} / {@link #onTransformation}
     * directly against a swapped-in {@code System.err}.
     *
     * @since 1.7.0
     */
    static final class DiagnosticListener extends AgentBuilder.Listener.Adapter {

        private final boolean debug;

        /**
         * Creates a listener.
         *
         * @param debug {@code true} to also log successful transformations and full error
         *              stack traces; {@code false} for errors-only, one line each
         */
        DiagnosticListener(boolean debug) {
            this.debug = debug;
        }

        /**
         * Logs a one-line diagnostic for a type the agent successfully wove — only when
         * {@code debug} is enabled; a no-op otherwise.
         *
         * @param typeDescription  the instrumented type
         * @param classLoader      the type's class loader (unused)
         * @param module           the type's module (unused)
         * @param loaded           whether the type was already loaded (unused)
         * @param dynamicType      the transformed type (unused)
         */
        @Override
        public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                     JavaModule module, boolean loaded, DynamicType dynamicType) {
            if (debug) {
                System.err.println("[ASYNC-TEST-AGENT] Instrumented " + typeDescription.getName());
            }
        }

        /**
         * Logs a single-line diagnostic for a type the agent failed to instrument. When
         * {@code debug} is enabled the throwable's full stack trace is appended after the
         * summary line.
         *
         * @param typeName    the fully-qualified name of the type that could not be woven
         * @param classLoader the type's class loader (unused)
         * @param module      the type's module (unused)
         * @param loaded      whether the type was already loaded (unused)
         * @param throwable   the failure raised during instrumentation
         */
        @Override
        public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                            boolean loaded, Throwable throwable) {
            System.err.println("[ASYNC-TEST-AGENT] Failed to instrument " + typeName + ": " + throwable);
            if (debug) {
                throwable.printStackTrace();
            }
        }
    }

    /**
     * Byte Buddy {@link Advice} class injected at the entry of every intercepted
     * <em>getter</em> (read accessor).
     *
     * <p>The {@code @Advice.OnMethodEnter} method executes inline at the call site,
     * not via reflection, so it does not appear in stack traces and incurs minimal
     * overhead after JIT compilation. The identifier is supplied by
     * {@link Advice.Origin @Advice.Origin} as a constant-pool string, and the
     * {@code isWrite} flag is hardcoded to {@code false}, so the prologue allocates
     * nothing per call.
     *
     * @since 1.7.0
     */
    public static final class ReadAccessAdvice {

        private ReadAccessAdvice() {}

        /**
         * Advice prologue for read accessors: records the accessing thread and the
         * combined {@code declaringClass.methodName} identifier as a read access before
         * the original getter body executes.
         *
         * <p>The identifier uses the {@code #t.#m} origin pattern (fully-qualified
         * declaring class name, a literal {@code '.'} separator, then the method name)
         * because Byte Buddy's origin parser rejects a doubled {@code ##} escape.
         *
         * @param identifier fully-qualified {@code declaringClass.methodName} of the
         *                   intercepted getter (compile-time constant)
         */
        @Advice.OnMethodEnter
        public static void enter(@Advice.Origin("#t.#m") String identifier) {
            TelemetryRegistry.recordAccess(
                    Thread.currentThread().threadId(), identifier, false);
        }
    }

    /**
     * Byte Buddy {@link Advice} class injected at the entry of every intercepted
     * <em>setter</em> (write accessor).
     *
     * <p>The {@code @Advice.OnMethodEnter} method executes inline at the call site,
     * not via reflection, so it does not appear in stack traces and incurs minimal
     * overhead after JIT compilation. The identifier is supplied by
     * {@link Advice.Origin @Advice.Origin} as a constant-pool string, and the
     * {@code isWrite} flag is hardcoded to {@code true}, so the prologue allocates
     * nothing per call.
     *
     * @since 1.7.0
     */
    public static final class WriteAccessAdvice {

        private WriteAccessAdvice() {}

        /**
         * Advice prologue for write accessors: records the accessing thread and the
         * combined {@code declaringClass.methodName} identifier as a write access before
         * the original setter body executes.
         *
         * <p>The identifier uses the {@code #t.#m} origin pattern (fully-qualified
         * declaring class name, a literal {@code '.'} separator, then the method name)
         * because Byte Buddy's origin parser rejects a doubled {@code ##} escape.
         *
         * @param identifier fully-qualified {@code declaringClass.methodName} of the
         *                   intercepted setter (compile-time constant)
         */
        @Advice.OnMethodEnter
        public static void enter(@Advice.Origin("#t.#m") String identifier) {
            TelemetryRegistry.recordAccess(
                    Thread.currentThread().threadId(), identifier, true);
        }
    }
}
