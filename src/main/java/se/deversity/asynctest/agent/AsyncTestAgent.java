package se.deversity.asynctest.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
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
 * Instrumentation is intentionally scoped to non-JDK, non-agent packages via
 * {@link ElementMatchers#not(net.bytebuddy.matcher.ElementMatcher) not(isBootstrapClassLoader())}
 * to avoid recursive instrumentation of JDK internals.
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

        new AgentBuilder.Default()
                // Skip bootstrap-loaded classes (java.*, jdk.*, sun.*) to avoid
                // infinite recursion when our advice itself calls into the JDK.
                .ignore(ElementMatchers.nameStartsWith("java.")
                        .or(ElementMatchers.nameStartsWith("jdk."))
                        .or(ElementMatchers.nameStartsWith("sun."))
                        .or(ElementMatchers.nameStartsWith("se.deversity.asynctest.")))
                .type(ElementMatchers.any())
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(FieldAccessAdvice.class)
                                      .on(ElementMatchers.isGetter()
                                              .or(ElementMatchers.isSetter()))))
                .installOn(inst);
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
