package se.deversity.asynctest;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import se.deversity.asynctest.runner.ConcurrencyRunner;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIPublicAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs a body the way {@code @AsyncTest} runs a test method, without JUnit Jupiter driving it:
 * N threads, M rounds, one {@code CyclicBarrier} per round, every detector the configuration
 * selects, the same licence gate, the same timeout and {@code failOn} semantics.
 *
 * <p>{@code @AsyncTest} is a Jupiter {@code @TestTemplate}, so it only runs inside a Jupiter test
 * class. Spock, ScalaTest, MUnit, kotest and {@code clojure.test} are JUnit Platform engines or
 * frameworks of their own, and a Jupiter template does not run inside them. This class is the
 * entry point for those: build an {@link AsyncTestConfig}, hand over the body, read the findings.
 *
 * <pre>{@code
 * AsyncTestConfig cfg = AsyncTestConfig.builder()
 *         .threads(8).invocations(200).detectAll(true).failOn(FailOn.NONE).build();
 * AsyncFindings findings = AsyncTestRunner.run(cfg, () -> counter.increment());
 * findings.assertReported("RaceConditionDetector");
 * }</pre>
 *
 * <p><strong>Detectors are opt-in on the builder.</strong> A bare {@code @AsyncTest} runs
 * {@link Preset#ESSENTIALS}; {@link AsyncTestConfig#builder()} starts from deadlock detection
 * alone. Say which detectors you want: {@code preset(Preset.ESSENTIALS)} for the annotation's
 * default, {@code detectAll(true)} for every detector, or {@code includes(...)} and individual
 * {@code detectXxx(true)} calls.
 *
 * <p>The body runs on the runner's worker threads with an {@link AsyncTestContext} installed, so
 * {@code AsyncTestContext.get()} and the {@code recordXxx} hooks work exactly as they do inside an
 * annotated test method. What the annotated path throws, this throws: a failing body surfaces as
 * the engine's {@link AssertionError} whose cause is the body's exception (N workers hitting one
 * defect are collapsed into one error, as for an annotated test); a hung body as the timeout
 * {@link AssertionError}; findings at or above {@link FailOn} as the gate's
 * {@link AssertionError} after a clean run; and, with {@code failOnBenchmarkRegression} set, a
 * benchmark regression as {@code BenchmarkRegressionException}, thrown after the gate let the run
 * through, so a body failure or a gated finding still outranks it. On a clean run the returned collector holds every
 * finding the run reported. When the run throws, nothing is returned; a caller who needs the
 * findings of a failed run registers its own {@link AsyncFindings#collect()} around the call,
 * exactly as an annotated test does from {@code @BeforeAll}.
 *
 * <p><strong>Identity.</strong> The engine names a run after the method it executes. A body has
 * no method, so every programmatic run is reported as
 * {@code se.deversity.asynctest.AsyncTestRunner$BodyHolder#} plus {@code name} (defaulting to {@code #run}): in
 * the {@code runner.*} log events, in the {@code failOn} message, and as the test id the finding
 * baseline is keyed on. Passing a distinct {@code name} gives each programmatic run its own
 * baseline and report identity.
 * {@code name} is carried into the engine's events, {@code failOn} message, and baseline id.
 * Each distinct {@code name} is baselined independently in that JVM.
 * Calling the two-argument {@link #run(AsyncTestConfig, Body)} uses {@code "run"} as the name,
 * preserving {@code AsyncTestRunner$BodyHolder#run} as the default identity.
 *
 * <p><strong>Thread safety.</strong> {@link #run} may be called from any thread. Two calls
 * running at the same time in one JVM behave as two annotated tests running at the same time:
 * the detector context is per run, the listener registry and the agent's telemetry callback are
 * JVM-wide, so findings from concurrent runs are collected by both collectors.
 *
 * @see AsyncTest
 * @see AsyncFindings
 * @since 1.9.4
 */
@AIContract(reason = "Public programmatic entry point for the N x M engine, called from non-Jupiter test frameworks in Kotlin, Scala, Groovy and Clojure. run(...) signatures and the rule that it throws exactly what the annotated path throws must not change without a major version bump.")
@AIPublicAPI
@API(status = Status.EXPERIMENTAL)
public final class AsyncTestRunner {

    private static final Logger log = LoggerFactory.getLogger(AsyncTestRunner.class);

    private AsyncTestRunner() { /* static entry point */ }

    /**
     * The code under test, run once per worker per round.
     *
     * <p>Declared to throw {@link Throwable} so a body can be any test code, including one that
     * fails with a checked exception or an assertion; the engine reports the failure as the cause
     * of the round's {@link AssertionError}. Not a {@link Runnable} because that would force every
     * checked exception through a wrapper of the body's own before the engine ever saw it.
     */
    @FunctionalInterface
    public interface Body {
        /**
         * Runs the code under test on the calling worker thread.
         *
         * @throws Throwable any failure of the code under test; it fails the run as an annotated
         *     test's failure would
         */
        void run() throws Throwable;
    }

    /**
     * Runs {@code body} under {@code config} and returns what the detectors reported.
     *
     * @param config threads, rounds, timeout, detectors and the {@code failOn} gate; the same
     *     resolution as an {@code @AsyncTest} annotation, via {@link AsyncTestConfig#builder()}
     * @param body the code under test, invoked {@code threads x invocations} times in total
     * @return the findings this run reported, already unregistered from the listener registry so
     *     it records nothing from later runs
     * @throws Throwable the round's {@link AssertionError} carrying the body's failure as its
     *     cause, a timeout {@link AssertionError}, or the {@code failOn} gate's
     *     {@link AssertionError}, or a {@code BenchmarkRegressionException} when
     *     {@code failOnBenchmarkRegression} is set and the run regressed; see the class Javadoc
     */
    public static AsyncFindings run(AsyncTestConfig config, Body body) throws Throwable {
        return run("run", config, body);
    }

    /**
     * Runs {@code body} under {@code config} with a caller-chosen name for the log.
     *
     * @param name what to call this run in the {@code runner.*} log events, the {@code failOn}
     *     message, and the baseline id (see the class Javadoc)
     *     for this programmatic run (see the class Javadoc)
     * @param config threads, rounds, timeout, detectors and the {@code failOn} gate
     * @param body the code under test, invoked {@code threads x invocations} times in total
     * @return the findings this run reported, already unregistered from the listener registry
     * @throws Throwable the round's {@link AssertionError} carrying the body's failure as its
     *     cause, a timeout {@link AssertionError}, or the {@code failOn} gate's
     *     {@link AssertionError}, or a {@code BenchmarkRegressionException} when
     *     {@code failOnBenchmarkRegression} is set and the run regressed
     */
    public static AsyncFindings run(String name, AsyncTestConfig config, Body body) throws Throwable {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(body, "body");
        BodyHolder holder = new BodyHolder(body);
        ReflectiveInvocationContext<Method> context = new BodyInvocationContext(holder);
        // The one place the caller's name is visible: the engine's own events say test=run.
        log.debug("runner.programmatic name={} threads={} invocations={} failOn={}",
                name, config.threads, config.invocations, config.failOn);
        // Collect around the run and close in the same frame that opened it, on every exit
        // path: the registry is JVM-wide, and a collector left registered records every later
        // run in the process. Findings stay readable after close(), so the caller can still
        // assert on them.
        AsyncFindings findings = AsyncFindings.collect();
        try {
            // The context names BodyHolder, in this library's package; the body's own class is in
            // the caller's, which is what the default agent attach weaves (#1042).
            ConcurrencyRunner.executeForCodeIn(context, config, codeUnderTest(body), name);
        } finally {
            findings.close();
        }
        return findings;
    }

    /**
     * {@return a class in the caller's package, for the default agent attach to scope by (#1042)}
     *
     * <p>The body's own class, which a Java, Kotlin or Scala lambda and a Clojure {@code reify}
     * are compiled into the caller's package for. A dynamic-Groovy closure coerced to
     * {@link Body} is a JDK proxy instead, whose class lives in {@code jdk.proxyN} (#1068); then
     * the first caller frame outside this class, the JDK and the Groovy runtime, which is the test
     * or specification that built the body. A caller in this library's own package is returned as
     * it is, so the attach still refuses to weave it.
     *
     * @param body the body about to run
     */
    static Class<?> codeUnderTest(Body body) {
        Class<?> bodyClass = body.getClass();
        if (!Proxy.isProxyClass(bodyClass)) {
            return bodyClass;
        }
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> !isRunnerOrRuntime(type))
                .findFirst())
                .orElse(bodyClass);
    }

    private static boolean isRunnerOrRuntime(Class<?> type) {
        String name = type.getName();
        return type == AsyncTestRunner.class || name.startsWith(AsyncTestRunner.class.getName() + "$")
                || name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.")
                || name.startsWith("groovy.") || name.startsWith("org.codehaus.groovy.")
                || name.startsWith("org.apache.groovy.");
    }

    /**
     * The one method the engine invokes. Public and reflectively reachable on purpose: the
     * engine calls {@code Method.invoke} on it from every worker.
     */
    public static final class BodyHolder {
        private final Body body;

        BodyHolder(Body body) {
            this.body = body;
        }

        /**
         * Delegates to the body; the engine reports this method's name.
         *
         * @throws Throwable whatever the body throws
         */
        public void run() throws Throwable {
            body.run();
        }
    }

    /**
     * The invocation context the engine expects, pointing at {@link BodyHolder#run()}. No
     * arguments: a body takes none.
     */
    private static final class BodyInvocationContext implements ReflectiveInvocationContext<Method> {
        private static final Method RUN = lookupRun();
        private final BodyHolder holder;

        BodyInvocationContext(BodyHolder holder) {
            this.holder = holder;
        }

        private static Method lookupRun() {
            try {
                return BodyHolder.class.getMethod("run");
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("BodyHolder.run() must exist", e);
            }
        }

        @Override
        public Class<?> getTargetClass() {
            return BodyHolder.class;
        }

        @Override
        public Optional<Object> getTarget() {
            return Optional.of(holder);
        }

        @Override
        public Method getExecutable() {
            return RUN;
        }

        @Override
        public List<Object> getArguments() {
            return List.of();
        }
    }
}
