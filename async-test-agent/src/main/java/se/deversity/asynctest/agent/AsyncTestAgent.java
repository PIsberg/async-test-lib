package se.deversity.asynctest.agent;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;
import se.deversity.asynctest.telemetry.TelemetryRegistry;
import se.deversity.vibetags.annotations.AICore;

import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * Java instrumentation agent that injects access telemetry into application classes by weaving
 * their JavaBean accessors, so detectors observe reads and writes without manual
 * {@code recordFieldAccess()} callbacks.
 *
 * <p><strong>What it does and does not see.</strong> The unit of observation is an accessor
 * <em>call</em>, not a field access: the weaver matches {@link ElementMatchers#isGetter()} and
 * {@link ElementMatchers#isSetter()}, so a field reached only from inside a method body, such as
 * the {@code count++} in an {@code increment()}, produces no event. Code that goes through getters
 * and setters is covered; code that touches its fields directly is not, and needs the manual
 * recording hooks on {@code AsyncTestContext}.
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
 * There are two ways to attach the agent:
 * <ul>
 *   <li><b>Launch flag (static attach).</b> Add to the JVM launch command:
 *       <pre>{@code -javaagent:async-test-agent-<version>.jar}</pre>
 *       This routes through {@link #premain(String, Instrumentation)} before
 *       {@code main()} runs, so every subsequently loaded class is woven at load time.</li>
 *   <li><b>Runtime self-attach (dynamic attach).</b> Call
 *       {@link #selfAttach()} / {@link #selfAttach(String)} from test setup (for example
 *       a JUnit {@code @BeforeAll} method). This uses Byte Buddy's
 *       {@code byte-buddy-agent} to attach the very same agent to the running JVM via
 *       {@link #agentmain(String, Instrumentation)} — no launch-flag edit required. See
 *       {@link #selfAttach(String)} for the retransformation semantics that let it also
 *       re-weave already-loaded classes.</li>
 * </ul>
 * The library JAR is agent-capable: its MANIFEST includes {@code Premain-Class},
 * {@code Agent-Class}, and {@code Can-Retransform-Classes: true}.
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
@AICore(
    sensitivity = "Critical",
    note = "The INSTALLED gate must stay at-most-once per JVM: every entry point (premain, agentmain, selfAttach) races on the same compareAndSet, and a second transformer would double-weave accesses and double-count every one. premain installs without retransformation because classes are woven as they load; agentmain must keep RETRANSFORMATION + disableClassFormatChanges(), which is only safe while neither weaver adds members — the Advice is a method-entry prologue, and FieldAccessWeaver inserts a stack-neutral, branch-free call before each field instruction, so frames stay valid and only maxStack grows (COMPUTE_MAXS, never COMPUTE_FRAMES, which would load classes from inside the agent). Nothing may throw out of premain — an exception there aborts JVM startup, which is why install() catches Throwable and releases the gate rather than propagating. The Premain-Class / Agent-Class manifest entries live in this module's jar, which is why attaching uses -javaagent:async-test-agent.jar."
)
public final class AsyncTestAgent {

    /**
     * At-most-once install gate, shared by every entry point ({@link #premain},
     * {@link #agentmain}, and {@link #selfAttach}). The first entry point to win the
     * {@code compareAndSet(false, true)} performs the weaving install; all later calls —
     * whether a redundant {@code premain}/{@code agentmain} or a {@code selfAttach} that
     * follows a launch-flag attach — return as no-ops. This makes self-attach idempotent
     * and guarantees a single transformer is installed per JVM, so a class's accessors are
     * never double-woven.
     */
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    /**
     * How many already-loaded classes go into one {@code retransformClasses} call.
     *
     * <p>Any bound would do; what matters is that there is one. The JVM's call is all-or-nothing,
     * so an unbounded batch means one unretransformable class costs every other class its
     * weaving. A few hundred keeps the number of JVM round trips small while leaving a failure
     * something the splitting reallocator can isolate in a handful of halvings.
     */
    private static final int RETRANSFORM_BATCH_SIZE = 256;

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
     * <pre>{@code -javaagent:async-test-agent.jar=includes=com.myapp;excludes=com.myapp.dto}</pre>
     * Whitespace is trimmed, empty entries are skipped, and unknown keys are ignored.
     * A {@code null} or blank argument leaves the default behavior (instrument every
     * non-ignored class) unchanged. Parsing never throws — an exception thrown from
     * {@code premain} would abort JVM startup.
     *
     * @param agentArgs  optional {@code includes}/{@code excludes} configuration, or
     *                   {@code null}
     * @param inst       the instrumentation handle provided by the JVM
     */
    public static void premain(@Nullable String agentArgs, Instrumentation inst) {
        // Static attach: classes are woven as they load, so retransformation of
        // already-loaded classes is unnecessary.
        install(agentArgs, inst, false);
    }

    /**
     * JVM agent entry point invoked when the agent is attached to an <em>already
     * running</em> JVM (dynamic attach) — either through the Attach API or via
     * {@link #selfAttach(String)}.
     *
     * <p>Unlike {@link #premain}, dynamic attach happens after arbitrary application
     * classes have already been loaded. To weave those already-loaded classes this entry
     * point installs with
     * {@link AgentBuilder.RedefinitionStrategy#RETRANSFORMATION} and
     * {@link AgentBuilder.Default#disableClassFormatChanges()}. The injected
     * {@link Advice} only inlines a method-entry prologue — it adds no fields, methods, or
     * interfaces — so the class schema is unchanged and retransformation is safe;
     * accessors of classes loaded <em>before</em> attach are therefore re-woven in place,
     * exactly like classes loaded afterwards. This behaviour is verified by
     * {@code SelfAttachTest}.
     *
     * <p>The {@code agentArgs} string is parsed identically to {@link #premain} (see
     * {@link AgentOptions}). Guarded by the shared at-most-once install gate, so a
     * {@code premain} attach followed by a dynamic attach installs only one transformer.
     *
     * @param agentArgs optional {@code includes}/{@code excludes}/{@code debug}
     *                  configuration, or {@code null}
     * @param inst      the instrumentation handle provided by the attach mechanism; must
     *                  support retransformation
     * @since 1.7.0
     */
    public static void agentmain(@Nullable String agentArgs, Instrumentation inst) {
        // Dynamic attach: re-weave classes that were already loaded before attach.
        install(agentArgs, inst, true);
    }

    /**
     * Attaches the agent to the currently running JVM at runtime, with no
     * {@code agentArgs}, so that <em>every</em> non-ignored class is instrumented.
     * Equivalent to {@code selfAttach(null)}.
     *
     * @throws IllegalStateException if the JVM forbids self-attachment; see
     *                               {@link #selfAttach(String)}
     * @since 1.7.0
     */
    public static void selfAttach() {
        selfAttach(null);
    }

    /**
     * Attaches the agent to the currently running JVM at runtime — no
     * {@code -javaagent:} launch flag required.
     *
     * <p>Obtains an {@link Instrumentation} handle via Byte Buddy's
     * {@link ByteBuddyAgent#install()} and routes it through the same install path as
     * {@link #agentmain}. Because dynamic attach runs after classes have loaded, the
     * install uses retransformation: accessors of classes loaded <em>before</em> the call
     * are re-woven in place, in addition to classes loaded afterwards (verified by
     * {@code SelfAttachTest}).
     *
     * <h4>When to call</h4>
     * Call once from test setup — for example a JUnit {@code @BeforeAll} method — before
     * the code under test exercises the accessors you want to observe. Then consume the
     * events through {@link TelemetryRegistry} (or a higher-level bridge).
     *
     * <h4>Idempotency and interaction with {@code -javaagent}</h4>
     * This method is idempotent and at-most-once per JVM: it shares the
     * {@link #INSTALLED} gate with {@link #premain} and {@link #agentmain}. If the agent
     * was already attached (via the launch flag or a prior {@code selfAttach}), the call
     * returns immediately without attaching again or double-weaving. It is therefore safe
     * to call unconditionally even when a {@code -javaagent:} flag may already be present,
     * and safe to call concurrently from multiple threads.
     *
     * <h4>Failure</h4>
     * Self-attachment is disabled by default on JDK&nbsp;9+ unless the target JVM was
     * started with {@code -Djdk.attach.allowAttachSelf=true}. When attachment is refused
     * (or otherwise fails) this method throws an {@link IllegalStateException} rather than
     * swallowing the error, so the failure is visible; the message directs the caller to
     * fall back to the {@code -javaagent:} launch flag.
     *
     * @param agentArgs optional {@code includes}/{@code excludes}/{@code debug}
     *                  configuration (see {@link AgentOptions}), or {@code null} to
     *                  instrument every non-ignored class
     * @throws IllegalStateException if the JVM forbids self-attachment (for example
     *                               {@code jdk.attach.allowAttachSelf=false}) or the
     *                               attach otherwise fails
     * @since 1.7.0
     */
    public static void selfAttach(@Nullable String agentArgs) {
        // Fast path: if a launch-flag premain (or a prior selfAttach) already installed the
        // transformer, skip the attach entirely. The authoritative at-most-once guarantee
        // is still the INSTALLED CAS inside install(), which also makes concurrent
        // selfAttach() calls safe: at most one wins and installs.
        if (INSTALLED.get()) {
            return;
        }
        Instrumentation inst;
        try {
            inst = ByteBuddyAgent.install();
        } catch (RuntimeException | Error ex) {
            throw new IllegalStateException(
                    "AsyncTestAgent.selfAttach() could not attach to the current JVM. "
                    + "Self-attachment is disabled by default on JDK 9+; start the JVM with "
                    + "-Djdk.attach.allowAttachSelf=true, or fall back to launching with "
                    + "-javaagent:async-test-agent-<version>.jar.", ex);
        }
        install(agentArgs, inst, true);
    }

    /**
     * Shared installation path used by {@link #premain} (static attach) and
     * {@link #agentmain} / {@link #selfAttach} (dynamic attach). Parses {@code agentArgs},
     * builds the ignore/type matchers from the resulting {@link AgentOptions}, and
     * installs the weaving agent on {@code inst}.
     *
     * <p>Guarded by the {@link #INSTALLED} at-most-once gate: the first caller to win the
     * CAS installs the transformer; every later call returns without installing, so a
     * single transformer is active per JVM and accessors are never double-woven.
     *
     * @param agentArgs   the raw agent argument string, or {@code null}
     * @param inst        the instrumentation handle to install on
     * @param retransform when {@code true}, install with
     *                    {@link AgentBuilder.RedefinitionStrategy#RETRANSFORMATION} and
     *                    {@link AgentBuilder.Default#disableClassFormatChanges()} so
     *                    already-loaded classes are re-woven (dynamic attach); when
     *                    {@code false}, only classes loaded after install are woven
     *                    (static attach)
     */
    private static void install(@Nullable String agentArgs, Instrumentation inst, boolean retransform) {
        if (!INSTALLED.compareAndSet(false, true)) {
            // Another entry point already installed the transformer for this JVM.
            return;
        }
        // Everything below is fallible, and this method runs from premain, where a propagating
        // exception aborts JVM startup before main() — the consumer's whole build dies, with a
        // stack trace pointing at an agent they added for diagnostics. The realistic trigger is
        // NoClassDefFoundError from TelemetryRegistry.start(): TelemetryRegistry lives in
        // async-test-lib, which is deliberately NOT shaded into this jar (relocating it would
        // make the advice feed a different registry than TelemetryBridge drains, silently
        // breaking the pipeline), so attaching to a JVM without the library on the system
        // classloader lands here. Degrading to "agent does nothing" is strictly better than
        // killing the JVM: the runner already logs runner.agent.absent when no events arrive.
        //
        // The gate is released on failure. It is claimed before the fallible work so that
        // concurrent callers cannot both install, but leaving it set after a failed install
        // would wedge the JVM in a state where a transformer was never installed and every
        // later selfAttach() takes the fast path and silently no-ops forever.
        try {
            installUnguarded(agentArgs, inst, retransform);
        } catch (Throwable t) { // NOPMD - broad by design: premain must never propagate
            INSTALLED.set(false);
            System.err.println("[ASYNC-TEST-AGENT] Agent installation failed; continuing without "
                    + "instrumentation. Detectors that rely on woven accesses will report "
                    + "nothing. Cause: " + t);
        }
    }

    /**
     * Performs the actual install. Separated from {@link #install} so the at-most-once gate and
     * its failure handling stay readable, and so every throwing path has exactly one catch.
     *
     * @param agentArgs   the raw agent argument string, or {@code null}
     * @param inst        the instrumentation handle to install on
     * @param retransform whether to re-weave already-loaded classes
     */
    private static void installUnguarded(@Nullable String agentArgs, Instrumentation inst,
                                         boolean retransform) {
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

        // Only the dynamic-attach path needs the coverage check: premain weaves at load time,
        // so "loaded but never consulted" is an empty set by construction.
        AttachCoverageReport.Discovery discovery =
                retransform ? new AttachCoverageReport.Discovery() : null;
        AgentBuilder builder = new AgentBuilder.Default()
                .with(discovery == null
                        ? new DiagnosticListener(options.debug())
                        : new AgentBuilder.Listener.Compound(
                                new DiagnosticListener(options.debug()), discovery));
        if (retransform) {
            // Dynamic attach: re-weave already-loaded classes. Neither Advice inlining nor the
            // field weaver adds new members, so disableClassFormatChanges() keeps
            // retransformation schema-safe.
            //
            // Batching and the reallocator are not tuning. Byte Buddy's default hands every
            // loaded class to Instrumentation.retransformClasses in ONE call, and that call is
            // all-or-nothing: a single type the JVM refuses re-weaves none of the others, and
            // the default RedefinitionStrategy.Listener swallows the failure. Attaching to a
            // suite with a large classpath therefore silently degraded to "weave only what
            // loads from here on", which is the failure the corpus eval hit when four more
            // libraries joined its classpath: instrumented types fell from 1074 to 200 and
            // every subject loaded before the attach went quiet. Fixed batches bound the blast
            // radius, the splitting reallocator halves a failing batch until the offending type
            // is alone, and RetransformDiagnosticListener says which type that was instead of
            // leaving a user to wonder why their detectors went silent.
            builder = builder
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.RedefinitionStrategy.BatchAllocator.ForFixedSize
                            .ofSize(RETRANSFORM_BATCH_SIZE))
                    // Reiterating, not the default SinglePass, and the ordering of these two
                    // calls is the builder's, not a preference: with(BatchAllocator) narrows to
                    // the interface that declares with(DiscoveryStrategy).
                    //
                    // Byte Buddy's HYBRID description strategy describes an already-loaded class
                    // by reflection, and reflection eagerly resolves every field and method
                    // signature type - so describing a class here LOADS the types it names.
                    // Those loads happen on this thread, inside the circularity lock doInstall
                    // holds for the whole pass, and ExecutingTransformer returns NO_TRANSFORMATION
                    // for them without calling any listener. SinglePass took its
                    // getAllLoadedClasses() snapshot before they existed, so retransformation
                    // never revisits them either: woven by nothing, with no error and no log
                    // line anywhere. Reiterating re-queries the loaded set until it stops
                    // growing, which is the pass that picks them up.
                    .with(AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Reiterating.INSTANCE)
                    .with(new AgentBuilder.RedefinitionStrategy.Listener.Compound(
                            AgentBuilder.RedefinitionStrategy.Listener.BatchReallocator.splitting(),
                            new RetransformDiagnosticListener(options.debug())))
                    .disableClassFormatChanges();
        }
        boolean weaveFields = options.fields();
        // Resolved once, here, rather than per transformation: the hook class lives in the library
        // jar, and a classpath that cannot see it must fail while installing the agent, not later
        // from inside somebody's woven test body.
        List<AsmVisitorWrapper> collectionSubstitutions =
                options.collections() ? collectionSubstitutions() : List.of();
        builder
                .ignore((typeDescription, classLoader, module, classBeingRedefined, protectionDomain) ->
                        typeIgnore.matches(typeDescription) || bootstrapIgnore.matches(classLoader))
                .type(typeMatcher(options.includes()))
                .transform((b, typeDescription, classLoader, module, protectionDomain) -> {
                    // The accessor Advice is the default mode's whole story and must stand down
                    // when field instructions are woven: a getter's body contains the GETFIELD,
                    // so with fields=true the Advice reported every accessor-shaped method a
                    // second time, with no receiver identity, no volatile flag and no enclosing
                    // monitor. That weaker duplicate re-merged every instance of a field under
                    // one identity-0 key and is exactly why Guava's cache entries kept a finding
                    // that the per-instance stream had already cleared.
                    DynamicType.Builder<?> woven = weaveFields
                            ? b.visit(FieldAccessWeaver.visitor())
                            : b.visit(Advice.to(ReadAccessAdvice.class)
                                             .on(ElementMatchers.isGetter()))
                               .visit(Advice.to(WriteAccessAdvice.class)
                                             .on(ElementMatchers.isSetter()));
                    // Direct field instructions are opt-in: they make a bare count++ observable,
                    // which accessor weaving structurally cannot do, at the cost of instrumenting
                    // every field access in every matched class.
                    // Monitor instructions come along whenever anything is being recorded: they
                    // are what separates a guarded access from a racing one. Field instructions
                    // are the part fields=true actually buys.
                    if (!weaveFields && !collectionSubstitutions.isEmpty()) {
                        woven = woven.visit(FieldAccessWeaver.visitor(false));
                    }
                    // Collection calls are opt-in for the same reason and answer a different blind
                    // spot: a class that keeps its state in a HashMap writes nothing of its own,
                    // so field weaving sees a correct class racing and reports nothing at all.
                    for (AsmVisitorWrapper substitution : collectionSubstitutions) {
                        woven = woven.visit(substitution);
                    }
                    return woven;
                })
                .installOn(inst);

        // Say which already-loaded classes the transformer was never handed. Nothing above can
        // report them: Byte Buddy calls a listener for a type it wove, ignored or failed on, and
        // calls nothing at all for one it never saw, which is why #321 stayed invisible through
        // an afternoon of otherwise healthy logs. Prints nothing when the set is empty, which is
        // the expected outcome now that discovery reiterates.
        if (discovery != null) {
            AttachCoverageReport.report(
                    AttachCoverageReport.unconsulted(inst, discovery.consulted(),
                            typeIgnore, typeMatcher(options.includes())),
                    options.debug());
        }
    }

    /**
     * Resolves the library-side hook class and builds the collection substitutions.
     *
     * <p>The hooks live in {@code async-test-lib}, which the agent does not depend on: the agent
     * jar carries Byte Buddy and nothing of the library, exactly as the module boundary requires.
     * Loading it by name here is the same arrangement the field weaver already has with
     * {@code TelemetryRegistry}, and it fails loudly at install time when the two are not on the
     * same classpath.
     */
    private static List<AsmVisitorWrapper> collectionSubstitutions() {
        try {
            ClassLoader loader = AsyncTestAgent.class.getClassLoader();
            Class<?> hooks = Class.forName(CollectionAccessWeaver.hooksClassName(), false, loader);
            Class<?> lockHooks = Class.forName(CollectionAccessWeaver.lockHooksClassName(), false, loader);
            Class<?> sharedHooks =
                    Class.forName(CollectionAccessWeaver.sharedHooksClassName(), false, loader);
            // Lock weaving rides along with collection weaving rather than being its own option:
            // recording an access without seeing the ReentrantLock that covered it reports correct
            // code, which is the same reason monitor weaving is not separately switchable.
            //
            // Shared-instance weaving rides along for the opposite reason: it cannot report
            // correct code. Its detectors fire only on an instance more than one thread touched,
            // and the three types in that table have no thread-safe subclass a call site could be
            // holding, so there is nothing for a user to switch off.
            List<AsmVisitorWrapper> all =
                    new java.util.ArrayList<>(CollectionAccessWeaver.substitutions(hooks));
            all.addAll(CollectionAccessWeaver.lockSubstitutions(lockHooks));
            all.addAll(CollectionAccessWeaver.sharedInstanceSubstitutions(sharedHooks));
            all.addAll(CollectionAccessWeaver.concurrencySubstitutions(
                    Class.forName(CollectionAccessWeaver.concurrencyHooksClassName(), false, loader)));
            return all;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "collections=true needs " + CollectionAccessWeaver.hooksClassName()
                            + " on the classpath; add the async-test-lib dependency, or drop the"
                            + " collections= option", e);
        }
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
        // Assembled at runtime so the Shade plugin's relocation cannot rewrite it: a
        // literal "net.bytebuddy." would be relocated along with the type references,
        // and the matcher would stop ignoring a consumer's own (unrelocated) Byte Buddy
        // — for example Mockito's. The shaded copy needs no entry of its own: it lives
        // under se.deversity.asynctest., which the next prefix already covers.
        String byteBuddyPrefix = String.join(".", "net", "bytebuddy") + ".";
        return ElementMatchers.<TypeDescription>nameStartsWith("java.")
                .or(ElementMatchers.nameStartsWith("jdk."))
                .or(ElementMatchers.nameStartsWith("sun."))
                .or(ElementMatchers.nameStartsWith("com.sun."))
                .or(ElementMatchers.nameStartsWith(byteBuddyPrefix))
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

    /**
     * Legacy Byte Buddy {@link Advice} class retained for binary compatibility with 1.6.0.
     *
     * <p>The agent no longer binds this advice: getters and setters are woven with
     * {@link ReadAccessAdvice} and {@link WriteAccessAdvice}, whose single
     * constant-pool {@code @Advice.Origin} identifier keeps the prologue
     * allocation-free. This class remains functional for external code that applied
     * {@code Advice.to(FieldAccessAdvice.class)} against a 1.6.0 artifact.
     *
     * @deprecated since 1.7.0 — use {@link ReadAccessAdvice} / {@link WriteAccessAdvice};
     *             this variant concatenates the identifier on every intercepted call.
     */
    @Deprecated(since = "1.7.0")
    public static final class FieldAccessAdvice {

        private FieldAccessAdvice() {}

        /**
         * Advice prologue: records the accessing thread and target field before
         * the original method body executes.
         *
         * @param className  fully-qualified declaring class name (compile-time constant)
         * @param methodName intercepted method name (compile-time constant)
         * @deprecated since 1.7.0 — see class-level note
         */
        @Deprecated(since = "1.7.0")
        @Advice.OnMethodEnter
        public static void enter(
                @Advice.Origin("#t") String className,
                @Advice.Origin("#m") String methodName) {
            TelemetryRegistry.recordAccess(
                    Thread.currentThread().threadId(), className, methodName);
        }
    }

    /**
     * Says which already-loaded class the JVM refused to re-weave, instead of losing it silently.
     *
     * <p>Retransformation failures do not reach {@link DiagnosticListener}: that one hears about
     * transformation, this one about the {@code retransformClasses} call around it. Paired with
     * {@code BatchReallocator.splitting()}, a failing batch is halved until the offending type
     * stands alone, so the single-type failure logged here names the actual culprit and every
     * other class in the original batch has already been re-woven by then. Intermediate batch
     * failures are the splitting working as intended and are logged only under {@code debug}.
     */
    static final class RetransformDiagnosticListener
            extends AgentBuilder.RedefinitionStrategy.Listener.Adapter {

        private final boolean debug;

        /**
         * Creates a listener.
         *
         * @param debug {@code true} to also log the batches that are about to be split, and full
         *              stack traces; {@code false} for one line per type that could not be
         *              re-woven
         */
        RetransformDiagnosticListener(boolean debug) {
            this.debug = debug;
        }

        /**
         * Reports a failed retransformation batch and leaves the retry to the reallocator.
         *
         * @param index     the batch's index (unused)
         * @param batch     the classes in the failed batch
         * @param throwable the failure the JVM raised
         * @param types     every type the redefinition considered (unused)
         * @return no additional retries; {@code BatchReallocator.splitting()} supplies those
         */
        @Override
        public Iterable<? extends List<Class<?>>> onError(int index, List<Class<?>> batch,
                                                          Throwable throwable,
                                                          List<Class<?>> types) {
            if (batch.size() == 1) {
                System.err.println("[ASYNC-TEST-AGENT] Could not re-weave already-loaded class "
                        + batch.get(0).getName() + ": " + throwable);
            } else if (debug) {
                System.err.println("[ASYNC-TEST-AGENT] Retransformation batch of " + batch.size()
                        + " failed, splitting to isolate the type: " + throwable);
            }
            if (debug) {
                throwable.printStackTrace();
            }
            return List.of();
        }
    }
}
