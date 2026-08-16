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
 * <p><strong>Detectors are opt-in on the builder.</strong> {@code @AsyncTest} defaults to
 * {@code detectAll = true}; {@link AsyncTestConfig#builder()} defaults every detector to off, so
 * a config built without {@code detectAll(true)}, a {@code preset(...)} or individual
 * {@code detectXxx(true)} calls runs the body under contention and detects nothing. Say which
 * detectors you want.
 *
 * <p>The body runs on the runner's worker threads with an {@link AsyncTestContext} installed, so
 * {@code AsyncTestContext.get()} and the {@code recordXxx} hooks work exactly as they do inside an
 * annotated test method. What the annotated path throws, this throws: a failing body surfaces as
 * the engine's {@link AssertionError} whose cause is the body's exception (N workers hitting one
 * defect are collapsed into one error, as for an annotated test); a hung body as the timeout
 * {@link AssertionError}; findings at or above {@link FailOn} as the gate's
 * {@link AssertionError} after a clean run. On a clean run the returned collector holds every
 * finding the run reported. When the run throws, nothing is returned; a caller who needs the
 * findings of a failed run registers its own {@link AsyncFindings#collect()} around the call,
 * exactly as an annotated test does from {@code @BeforeAll}.
 *
 * <p><strong>Identity.</strong> The engine names a run after the method it executes. A body has
 * no method, so every programmatic run is reported as
 * {@code se.deversity.asynctest.AsyncTestRunner$BodyHolder#run}: in the {@code runner.*} log events,
 * in the {@code failOn} message, and as the test id the finding baseline is keyed on. Baselining
 * a finding for one programmatic run therefore suppresses it for all of them in that JVM.
 * {@code name} is carried into the {@code runner.programmatic} log event only, until the engine
 * learns a display name.
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
     *     {@link AssertionError}; see the class Javadoc
     */
    public static AsyncFindings run(AsyncTestConfig config, Body body) throws Throwable {
        return run("run", config, body);
    }

    /**
     * Runs {@code body} under {@code config} with a caller-chosen name for the log.
     *
     * @param name what to call this run in the {@code runner.programmatic} log event; it does
     *     not reach the engine's own events or the baseline id (see the class Javadoc)
     * @param config threads, rounds, timeout, detectors and the {@code failOn} gate
     * @param body the code under test, invoked {@code threads x invocations} times in total
     * @return the findings this run reported, already unregistered from the listener registry
     * @throws Throwable the round's {@link AssertionError} carrying the body's failure as its
     *     cause, a timeout {@link AssertionError}, or the {@code failOn} gate's
     *     {@link AssertionError}
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
            ConcurrencyRunner.execute(context, config);
        } finally {
            findings.close();
        }
        return findings;
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
