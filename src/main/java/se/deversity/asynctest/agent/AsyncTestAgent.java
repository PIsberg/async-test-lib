package se.deversity.asynctest.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.lang.instrument.Instrumentation;

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
 * getter and setter (matched by {@link ElementMatchers#isGetter()} /
 * {@link ElementMatchers#isSetter()}) at class-load time and inserts an
 * {@link Advice @Advice} prologue that routes to {@link TelemetryRegistry#recordAccess}.
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
 * @since 1.6.0
 */
public final class AsyncTestAgent {

    private AsyncTestAgent() {}

    /**
     * JVM agent entry point invoked before {@code main()}.
     *
     * @param agentArgs  optional comma-separated configuration (reserved for future use)
     * @param inst       the instrumentation handle provided by the JVM
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        TelemetryRegistry.start();

        // Byte Buddy's AgentBuilder.ignore(...) REPLACES the built-in default ignore
        // matcher, so this call must re-establish every exclusion the default provided:
        // name-based prefixes and synthetic types (see ignoreMatcher()) plus every type
        // loaded by the bootstrap class loader. A RawMatcher lambda composes these with
        // OR semantics (the two-argument ignore(typeMatcher, classLoaderMatcher) overload
        // would AND them, which is not what we want here).
        ElementMatcher<? super TypeDescription> typeIgnore = ignoreMatcher();
        ElementMatcher<? super ClassLoader> bootstrapIgnore = ElementMatchers.isBootstrapClassLoader();

        new AgentBuilder.Default()
                .ignore((typeDescription, classLoader, module, classBeingRedefined, protectionDomain) ->
                        typeIgnore.matches(typeDescription) || bootstrapIgnore.matches(classLoader))
                .type(ElementMatchers.any())
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(FieldAccessAdvice.class)
                                      .on(ElementMatchers.isGetter()
                                              .or(ElementMatchers.isSetter()))))
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
     * Byte Buddy {@link Advice} class injected at the entry of every intercepted
     * getter/setter method.
     *
     * <p>The {@code @Advice.OnMethodEnter} method executes inline at the call site,
     * not via reflection, so it does not appear in stack traces and incurs minimal
     * overhead after JIT compilation.
     */
    public static final class FieldAccessAdvice {

        private FieldAccessAdvice() {}

        /**
         * Advice prologue: records the accessing thread and target field before
         * the original method body executes.
         *
         * @param className  fully-qualified declaring class name (compile-time constant)
         * @param methodName intercepted method name (compile-time constant)
         */
        @Advice.OnMethodEnter
        public static void enter(
                @Advice.Origin("#t") String className,
                @Advice.Origin("#m") String methodName) {
            TelemetryRegistry.recordAccess(
                    Thread.currentThread().threadId(), className, methodName);
        }
    }
}
