# Plan: first-class support for Kotlin, Scala, Groovy and Clojure

Investigated 2026-08-16. The question was whether `@AsyncTest` works from the other JVM
languages and, where it does, what a consumer needs to know and what should prove it in CI.
The short answer: it works from all four today, because `@AsyncTest` is a JUnit 5
`@TestTemplate` and every one of these languages can produce a JUnit 5 test class. What is
missing is proof that runs on every build, the language-specific traps written down where a
consumer will find them, and two library changes (one small, one design decision) that decide
how good the experience is beyond "the body runs".

Everything marked **verified** below was run on 2026-08-16 against the published
`async-test-lib:1.9.3` from Maven Central, JDK 21, JUnit 5.13.4, in throwaway Maven projects
outside the repo. Everything marked **read** was concluded from source or bytecode and has not
been executed; step 1 executes it.

## What is already true

| Language | Toolchain used | `@AsyncTest` discovered and run | Body ran 8x200 times | Contention real |
|---|---|---|---|---|
| Kotlin 2.4.10 | kotlin-maven-plugin, Gradle Kotlin DSL | **verified**, `examples/128-kotlin-lost-update`, in the examples CI shard | yes (that example) | yes |
| Groovy 5.0.7 | gmavenplus-plugin 4.2.1 | **verified** | `safe=1600` | `plain=1355` (245 lost updates on a static `long`) |
| Scala 3.3.6 | scala-maven-plugin 4.9.5 | **verified** | `safe=1600` | `plain=1595` (5 lost updates on an `object` `var`) |
| Clojure 1.12.1 | clojure-maven-plugin 1.9.3, `gen-class` AOT | **verified** | `safe=1600` | `cell=1587` (13 lost updates on a `long-array` cell) |

"Body ran" was measured with a static `@AfterAll` printing an `AtomicInteger` the safe twin
incremented; "contention real" with a deliberately unguarded twin losing updates. Neither run
asserted a detector finding, which is the gap step 1 closes.

## Language-specific facts a consumer needs

These are the things that cost time in the spikes. They belong in each fixture's README and in
one section of `docs/INDEX.md`-routed documentation, not in tribal knowledge.

**Kotlin.** Nothing to configure. Annotation attributes are named arguments. Kotlin properties
compile to `getX`/`setX`, so the agent's default accessor weaving sees them (read; the
agent's `AsyncTestAgent` matches `ElementMatchers.isGetter()`/`isSetter()`). `suspend fun`
test bodies are not JUnit-invokable; a coroutine body must be wrapped in `runBlocking`. `by lazy`
is `SYNCHRONIZED` by default and is therefore not a lazy-init race subject unless
`LazyThreadSafetyMode.NONE` is chosen. `object` state is a JVM static and behaves like one.

**Groovy.** `gmavenplus-plugin` needs `<targetBytecode>21</targetBytecode>`; the default 1.8
is refused by Groovy 5 (verified: the build fails without it). Groovy properties compile to
`getX`/`setX`, so accessor weaving applies (read). Dynamic dispatch goes through `invokedynamic`
call sites; detectors that inspect stack frames or `holdsLock` may see extra frames, and a
fixture should run once with `@CompileStatic` and once without to know whether that matters
(unverified). Spock is a JUnit Platform engine, not Jupiter, so `@AsyncTest` does not work
inside a Spock `Specification`; plain JUnit 5 in Groovy does. That limitation should be stated
rather than discovered.

**Scala.** `@AsyncTest` on a `def` in a `class` is discovered as-is (verified, Scala 3).
`@AfterAll` on the companion `object` works because Scala 3 emits static forwarders (verified).
Scala accessors are `x()` and `x_$eq(v)`, not `getX`/`setX` (verified with `javap` on the spike:
`public long plain(); public void plain_$eq(long);`), so the agent's default accessor weaving
does not see Scala `var` reads and writes; `-Dasynctest.agent=fields=true` weaves the field
instructions inside those accessors and does (read; step 4 measures it). sbt users need
`sbt-jupiter-interface` to run Jupiter at all; the fixture is Maven and Gradle like the rest of
the repo, and the README says what sbt needs.

**Clojure.** This is the one with real friction, and all of it is expressible.
- Clojure has no annotation syntax; the test class is a `gen-class` with `:methods`, and the
  annotation goes in the method-name metadata:
  `[^{se.deversity.asynctest.AsyncTest {:threads #=(int 8) :invocations #=(int 200)}} name [] void]`.
- Numeric literals are `Long`. `genclass` passes annotation values to ASM unchanged
  (`clojure/core.clj`, `add-annotation`, the `:else (.visit av name v)` branch), so a bare `8`
  produces `AnnotationTypeMismatchException: ... threads() (Found data of type java.lang.Long[8])`
  at discovery (verified). `#=(int 8)` read-evaluates to an `Integer` and works (verified).
  `(int 8)` without `#=` does not: metadata is not evaluated (verified, `gen-class` throws at
  compile time).
- Static lifecycle methods (`@AfterAll`, `@BeforeAll`) need `^{:static true}` on the signature
  vector, not on the method name: `^{:static true} [^{org.junit.jupiter.api.AfterAll {}} afterAll [] void]`
  (verified both ways; the wrong placement compiles to an instance method and JUnit refuses it).
- AOT compilation is required (`clojure-maven-plugin` `testCompile`), and Surefire needs
  `<includes><include>**/*Test.class</include></includes>` because the compiled class name
  (`spike.CounterTest`) does not follow from the namespace file name.
- The idiomatic alternative, `clojure.test` `deftest`, cannot use `@AsyncTest` at all. It could
  use a programmatic runner; see the design decision below.

## The two library-side items

**A. Programmatic entry point (design decision, not started).** `ConcurrencyRunner.execute`
takes a JUnit `ReflectiveInvocationContext<Method>`; there is no way to run the N x M engine
over a lambda. Clojure (`clojure.test`), Scala (ScalaTest, MUnit), Kotlin (kotest) and Groovy
(Spock) all have native test frameworks that are not Jupiter, and today the only answer for
them is "write a Jupiter class in our language". A public
`AsyncTestRunner.run(AsyncTestConfig, Runnable)` returning the findings would make
`(deftest ... (async-test/run cfg #(...)))` and `test("...") { AsyncTestRunner.run(cfg) { ... } }`
possible. It is an API addition and belongs in the deferred v2 list
([roadmap-v2.md](roadmap-v2.md)) with a japicmp-visible minor bump; it is not needed for the
fixtures below, which use Jupiter from every language. Decide it separately.

**B. Scala accessor weaving (small, measurable).** Either document that Scala needs
`fields=true`, or extend the agent's accessor matcher to `name()`/`name_$eq()` pairs whose body
is a single field read or write. The second is a change to a Critical core element
(`AsyncTestAgent`, see the guardrail note) and must keep the weaver stack-neutral and
`COMPUTE_MAXS`-only. Step 4 measures which is worth doing: if `fields=true` already yields the
same detector findings from Scala as from Java, the documentation line is enough.

## Plan

Each step is a PR. Order matters only where stated. Status 2026-08-16: steps 1, 2 and 3 are done
in one PR (`consumer-fixture-langs/`, the CI steps, [JVM_LANGUAGES.md](../JVM_LANGUAGES.md));
step 4 is measured (below); step 5 shipped as `AsyncTestRunner` (1.10.0), with a
`clojure.test` fixture in `consumer-fixture-langs/clojure`.

**1. Fixtures that prove it: `consumer-fixture-langs/`** (Kotlin, Groovy, Scala, Clojure; one
Maven module each, plus the Gradle twin the repo convention requires, versions read from the
root `pom.xml` per invariant 6). Sibling of `consumer-fixture/`, not inside it: the existing
fixture is the subject of the seven-version JUnit compatibility matrix, and adding four
compilers to it would multiply that matrix's wall-clock and couple every language toolchain to
every Jupiter version. Each module has the same three-test shape:
- the safe twin under `@AsyncTest(threads = 8, invocations = 200, detectAll = true)`, green;
- the buggy twin, feeding `AsyncTestContext.get().sharedRaceConditionDetector().recordFieldWrite(...)`
  from the language, with `failOn = FailOn.NONE`;
- an `AsyncFindings.collect()` registered in `@BeforeAll` and `assertReported("RaceConditionDetector")`
  in a static `@AfterAll`, exactly as `consumer-fixture/.../ConsumerFindingsAssertionTest` does
  in Java. That is what turns "the body ran" into "a detector fired and a consumer could assert
  it from this language". Without it a language fixture is reachability, not detection.
Each README states the language facts above, with the exact error a consumer would otherwise
hit. Kotlin's module can start as a copy of `examples/128-kotlin-lost-update` with the findings
assertion added.

**2. CI: one `jvm-languages` job in `e2e-tests.yml`**, after the parent install, running the
four modules on JDK 21 and 25. The runner is `harden-runner` with `egress-policy: block`; the
job needs `repo1.maven.org:443` and `plugins.gradle.org:443` (and whatever the Scala and
Clojure compilers fetch on first run) in `allowed-endpoints`, or it dies with an `ECONNREFUSED`
that looks like a network flake. Add the job to the final `needs:` gate so a red language
fixture blocks the merge like a red consumer fixture does. Cache `~/.m2` keyed on the four
poms; the Scala and Clojure toolchains are the slow first download.

**3. Docs.** A "From other JVM languages" section routed from `docs/INDEX.md`, one paragraph
per language linking its fixture, and the Spock and `clojure.test` limitations stated up
front. `README.md` currently says "works from Kotlin and Groovy too"; it can say all four and
link the section. `DocsIndexCoverageTest` enforces the routing.

**4. Measure the agent from Scala.** Done 2026-08-16, in a throwaway project against the
published 1.9.3 with `-Dasynctest.agent=includes=spike,fields=true` and the `async-test-agent`
artifact on the test classpath, no manual hook: an unguarded `counter += 1` on a Scala `var`
reported `AtomicityValidator` and nothing from `RaceConditionDetector`; a Java `counter++`
under the same agent reported exactly the same. With `fields=true`, Scala and Java are
indistinguishable to the agent, so item B closes as the documentation line in
[JVM_LANGUAGES.md](../JVM_LANGUAGES.md), not an agent change. (That field weaving feeds the
atomicity detector rather than the race hook is a property of the agent, not of the language,
and out of scope here.)

**5. Item A, decided and shipped.** `AsyncTestRunner.run(config, body)` (1.10.0,
`@API(EXPERIMENTAL)`) is an adapter over the unchanged `ConcurrencyRunner`: the body becomes the
`ReflectiveInvocationContext` the engine already takes, so nothing Critical moved. Two
limitations are documented rather than solved: detectors are opt-in on the builder, and every
programmatic run shares one identity (`AsyncTestRunner$BodyHolder#run`) in the log and the
finding baseline; a display name threaded through the engine is the follow-up if a consumer
needs per-run baselines. Proven from `clojure.test` by `programmatic_runner_test.clj`, both
directions.

## Cost

Step 1 is four small modules with the same shape; the Clojure one is the only one with
non-obvious build configuration, and the spike above is a working starting point. Step 2 is one
CI job. Steps 3 and 4 are an afternoon each. Step 5 is a design decision first and a minor
release second. Nothing here changes the runner, the detectors or the licence gate.
