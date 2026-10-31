# Using `@AsyncTest` from Kotlin, Groovy, Scala and Clojure

`@AsyncTest` is a JUnit 5 `@TestTemplate`, so it works from any language that produces a JUnit
Jupiter test class. Four are proven on every build by
[`consumer-fixture-langs/`](../consumer-fixture-langs/README.md): each language runs an
unguarded write that `RaceConditionDetector` must report and a guarded twin it must not, and
asserts both through `AsyncFindings`. This page is what a consumer in each language needs to
know. The investigation behind it is
[analysis/jvm-languages-plan.md](analysis/jvm-languages-plan.md).

What is the same everywhere: the annotation goes on a public void no-arg method, the
attributes are the ones in [USAGE.md](USAGE.md), findings are asserted after the run with
[`AsyncFindings`](ASYNC_ASSERT.md) in `@AfterAll` (detectors analyse after the last round, so
nothing can be observed from inside the body), and `-Dlicense.mock.mode=true` or
`licenseMockMode = true` on the annotation applies as in Java.

## Kotlin

```kotlin
class InventoryTest {
    private var count = 0

    @AsyncTest(threads = 8, invocations = 200)
    fun concurrentIncrements() { count++ }
}
```

Nothing to configure. Attributes are named arguments. `@BeforeAll` / `@AfterAll` live in a
`companion object` with `@JvmStatic`. Kotlin properties compile to `getX`/`setX`, so the
[agent](AGENT.md)'s default accessor weaving sees them. A `suspend fun` is not JUnit-invokable:
wrap a coroutine body in `runBlocking`. An `@AsyncTest suspend fun` fails the run with a message
that says so, whether or not `kotlin-reflect` is on the test classpath (#1146). `by lazy` is `SYNCHRONIZED` by default, so it is not a
lazy-init race subject unless you choose `LazyThreadSafetyMode.NONE`. Coroutine misuse has two rules
so far, both reported by `BLOCKING_ON_NON_BLOCKING_THREAD` when the agent is attached with
`kotlinx.coroutines` and your own package in its `includes=`. A blocking call on a
`Dispatchers.Default` worker is reported; the same worker running `Dispatchers.IO` work is not
(#993). A `runBlocking` entered on any dispatcher worker, `Dispatchers.Default` or `Dispatchers.IO`,
is reported, because the caller is itself a coroutine, and so is one entered by a coroutine on any
other thread, which the detector recognises by a `kotlinx.coroutines` frame below the call: nested
in another `runBlocking`, or on an executor dispatcher whose threads are plain `Thread`s: an
`asCoroutineDispatcher` over a JDK `ScheduledThreadPoolExecutor`, or `newSingleThreadContext`, whose
`Executors.unconfigurableExecutorService` wrapper the woven `execute` looks through to the pool
behind it (#1107). On an ordinary thread outside any coroutine, its intended use, nothing is
reported (#1007). A `Mutex` treated as reentrant
and races across suspensions need a coroutine owner model and are still
[#1007](https://github.com/PIsberg/async-test-lib/issues/1007).
Fixture:
[`consumer-fixture-langs/kotlin`](../consumer-fixture-langs/kotlin); a fuller example with a
buggy and a fixed twin: [`examples/128-kotlin-lost-update`](../examples/128-kotlin-lost-update).

## Groovy

```groovy
class InventoryTest {
    private int count = 0

    @AsyncTest(threads = 8, invocations = 200)
    void concurrentIncrements() { count++ }
}
```

Plain JUnit 5, not Spock. Spock is its own JUnit Platform engine and a Jupiter `@TestTemplate`
does not run inside a `Specification`. With `gmavenplus-plugin`, Groovy 5 needs
`<targetBytecode>21</targetBytecode>`; the plugin's 1.8 default is refused with
`Target bytecode 1.8 isn't accepted by Groovy 5.0.0-alpha-1 or newer`. Groovy properties
compile to `getX`/`setX`, so accessor weaving applies. Fixture:
[`consumer-fixture-langs/groovy`](../consumer-fixture-langs/groovy).

## Scala

```scala
class InventoryTest {
  private var count = 0

  @AsyncTest(threads = 8, invocations = 200)
  def concurrentIncrements(): Unit = count += 1
}
```

Discovered as-is (verified with Scala 3.3). `@BeforeAll` / `@AfterAll` go on the companion
`object`, which Scala 3 exposes through static forwarders. One thing that is Scala-specific: a
`var` compiles to accessors `count()` and `count_$eq(int)`, not `getCount`/`setCount`, so the
agent's default accessor weaving does not see Scala fields. Use the explicit
`recordFieldWrite` hook, or run the agent with `fields=true`, which weaves the field
instructions themselves. Measured 2026-08-16 with `-Dasynctest.agent=includes=<pkg>,fields=true`
on the published 1.9.3: an unguarded `counter += 1` on a Scala `var` and an unguarded
`counter++` on a Java field produce the same finding (`AtomicityValidator` reports the
read-modify-write; `RaceConditionDetector` reports neither, because field weaving feeds the
atomicity detector, not the race hook). With `fields=true`, Scala is Java. Under sbt, Jupiter needs `sbt-jupiter-interface`; the fixture is Maven
and Gradle. Fixture: [`consumer-fixture-langs/scala`](../consumer-fixture-langs/scala).

## Clojure

Clojure has no annotation syntax, so the test class is a `gen-class` and the JUnit annotations
are metadata on the method signatures:

```clojure
(ns example.inventory-test
  (:gen-class
    :name example.InventoryTest
    :prefix "-"
    :methods [[^{se.deversity.asynctest.AsyncTest {:threads #=(int 8) :invocations #=(int 200)}}
               concurrentIncrements [] void]
              ^{:static true} [^{org.junit.jupiter.api.AfterAll {}} afterAll [] void]]))

(defonce cell (long-array 1))
(defn -concurrentIncrements [this] (aset ^longs cell 0 (inc (aget ^longs cell 0))))
(defn -afterAll [] ...)
```

Three things that are not obvious, each verified the hard way:

- **`int` elements need an `Integer`.** Clojure literals are `Long`, and `gen-class` hands the
  value to ASM unchanged, so `{:threads 8}` compiles but discovery throws
  `AnnotationTypeMismatchException ... (Found data of type java.lang.Long[8])`. `#=(int 8)`
  read-evaluates to an `Integer` and works; `(int 8)` without `#=` is not evaluated. Enum
  elements take the symbol: `:failOn se.deversity.asynctest.FailOn/NONE`.
- **Static lifecycle methods** take `^{:static true}` on the signature vector, not on the name.
  On the name it compiles to an instance method and JUnit refuses it.
- **AOT is required** (`clojure-maven-plugin` `testCompile`), and Surefire must include
  `**/*Test`, because the class is named by `:name`, not by the file. Not `**/*Test.class`: under
  Surefire 3.6.0 that pattern selects the classes and runs none (#1078).

`clojure.test` `deftest` cannot use `@AsyncTest`; use `AsyncTestRunner` from a deftest instead
(next section). Fixture: [`consumer-fixture-langs/clojure`](../consumer-fixture-langs/clojure).

## Native test frameworks

Spock, ScalaTest, MUnit, kotest and `clojure.test` are not Jupiter, and `@AsyncTest` does not
run inside them. Since 1.9.4 the engine is also a method call:
[`AsyncTestRunner.run(config, body)`](usage/other-ways-to-run.md#running-without-the-annotation-asynctestrunner-194)
runs the body N x M under the detectors the config selects and returns the `AsyncFindings`.
From `clojure.test`:

```clojure
(deftest unguarded-writes-are-reported
  (let [cfg  (-> (AsyncTestConfig/builder) (.threads 8) (.invocations 200)
                 (.detectAll true) (.failOn FailOn/NONE) (.build))
        body (reify AsyncTestRunner$Body (run [_] (counter/increment)))
        findings (AsyncTestRunner/run cfg body)]
    (is (seq (.violationsFrom findings "RaceConditionDetector")))))
```

Two things the annotation does for you that the builder does not: a detector selection (the
builder starts from deadlock detection alone; `preset(Preset.ESSENTIALS)` is the annotation's
default, `detectAll(true)` every detector), and a per-test identity (every programmatic run is
`AsyncTestRunner$BodyHolder#run` in the log and the finding baseline). The body is a `reify` of
`AsyncTestRunner$Body`, not a fn: `Body` declares `throws Throwable` and Clojure has no SAM
conversion for fns. With `async-test-agent` on the test classpath and no flag, the runner attaches
the agent scoped to the package of the body's own class (#1042), which for a lambda or a `reify` is
the caller's package or namespace; a dynamic-Groovy closure coerced to `Body` is a JDK proxy, so for
one the runner uses the calling class instead (#1068), so a bare field race in the body is reported the way
it is under `@AsyncTest`; see [docs/agent/attaching.md](agent/attaching.md). Fixture, both directions, run by `clojure-maven-plugin`'s `test` goal:
[`consumer-fixture-langs/clojure/.../programmatic_runner_test.clj`](../consumer-fixture-langs/clojure/src/test/clojure/se/deversity/asynctest/fixture/programmatic_runner_test.clj).
The same call works from a Spock `Specification`, a ScalaTest suite or a kotest spec; the
Groovy, Scala and Kotlin fixtures use Jupiter and are not repeated in those frameworks.
