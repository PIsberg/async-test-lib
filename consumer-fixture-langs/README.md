# Consumer fixture: JVM languages

`@AsyncTest` driven from Kotlin, Groovy, Scala and Clojure, each module a downstream consumer
of the published `async-test-lib` artifact (resolved from `mavenLocal()` first, then Central,
exactly like [`consumer-fixture/`](../consumer-fixture/README.md)). It exists so "works from
other JVM languages" is a claim CI checks on every build rather than a sentence in the README.

The investigation behind it, and what each language needed:
[docs/analysis/jvm-languages-plan.md](../docs/analysis/jvm-languages-plan.md).

## What each module proves

Every module has the same two classes, and the pair is the point:

| Class | Body | Asserted in `@AfterAll` |
|---|---|---|
| `LostUpdateIsReportedTest` | eight threads write one field, no lock, `recordFieldWrite` hook | `AsyncFindings.assertReported("RaceConditionDetector")` |
| `GuardedCounterStaysSilentTest` | the same write and hook under the language's monitor lock | `AsyncFindings.assertNotReported("RaceConditionDetector")` |

`RaceConditionDetector` records `Thread.holdsLock(owner)` on the accessing thread at access
time, so the guarded twin (`synchronized(this)` in Kotlin and Groovy, `this.synchronized` in
Scala, `(locking this ...)` in Clojure) must stay silent while the unguarded twin must be
reported. One class alone would pass against a detector that reports every write, or one that
reports nothing. Both directions were broken deliberately on 2026-08-16 (lock removed from the
guarded twin, lock added to the unguarded twin) in Kotlin and Clojure and both assertions went
red with the expected messages.

The assertion lives in `@AfterAll` because detectors analyse after the last round; a finding
cannot be observed from inside the test body. `failOn = FailOn.NONE` keeps the report from
failing the test itself, so the `@AfterAll` assertion is the only thing that can go red.

## Kotlin only: the wait-loop shape a compiler chooses

Kotlin carries a second pair the other three languages do not, because it is the only toolchain
here that could disagree with javac about how a loop is compiled.

| Class | Body | Asserted in `@AfterAll` |
|---|---|---|
| `KotlinDoWhileWaitIsReportedTest` | `do { wait() } while (!ready)` after a lost notify | a `MissedSignal` finding is present |
| `KotlinWaitLoopIsNotReportedTest` | the same bounded poll written as `while (!ready)` | no `MissedSignal` finding |

The agent's `MISSED_SIGNAL` rule reads the shape the compiler emitted, not the source: a correct
predicate loop and `do { wait() } while (...)` differ in their backward jump. A compiler that
rotates loops puts the test after the body, which under a javac-only rule reads as the bug and
would report every bounded poll in the codebase. ECJ rotates, javac does not, and kotlinc was
checked by hand at 2.4.10 and by nothing since ([#710], [#714]). This pair is what re-checks it on
every Kotlin bump. Verified by breaking it: inverting the javac-shape half of the rule in
`CollectionAccessWeaver` turns the silent test red and leaves the firing one green, which is what a
kotlinc that started rotating loops would look like.

Unlike the four-language pair above, these two need the agent, because `collections=true` is what
substitutes `Object.wait`. Both builds attach `async-test-agent` with `-javaagent` rather than a
self-attach, so a refused attach fails rather than turning the gates into skips, and both scope
`includes=` to `com.example.kotlinfixture` so the race-condition pair keeps running unwoven. That
package sits outside `se.deversity.asynctest` deliberately: the weaver refuses to substitute inside
the library's own root, so a wait bean under `se.deversity.asynctest.fixture` is never woven at all
and both tests would measure nothing. The firing half is what caught that.

[#710]: https://github.com/PIsberg/async-test-lib/issues/710
[#714]: https://github.com/PIsberg/async-test-lib/issues/714

## Run it

```bash
mvn install -DskipTests -Djacoco.skip=true      # publish the in-progress artifact to ~/.m2
mvn -f consumer-fixture-langs/pom.xml test       # all four languages
./gradlew -p consumer-fixture-langs test         # Kotlin, Groovy, Scala (see below)
```

Clojure is Maven-only. Gradle has no first-party Clojure plugin, clojurephant's AOT model
differs from `clojure-maven-plugin`'s, and Maven is the canonical build here.

## Per-language notes

**Kotlin.** Nothing to configure. Annotation attributes are named arguments; the JUnit lifecycle
methods live in a `companion object` with `@JvmStatic`. Kotlin properties compile to
`getX`/`setX`, so the agent's default accessor weaving sees them. A `suspend fun` is not
JUnit-invokable; wrap a coroutine body in `runBlocking`. `by lazy` is `SYNCHRONIZED` by default.

**Groovy.** Plain JUnit 5, not Spock: Spock is its own JUnit Platform engine and `@AsyncTest`
is a Jupiter `@TestTemplate`, so it does not run inside a `Specification`.
`gmavenplus-plugin` needs `<targetBytecode>21</targetBytecode>`; Groovy 5 refuses the 1.8
default with `Target bytecode 1.8 isn't accepted by Groovy 5.0.0-alpha-1 or newer`.

**Scala.** `@AsyncTest` on a `def` in a `class` is discovered as-is; `@BeforeAll`/`@AfterAll`
go on the companion `object`, which Scala 3 exposes through static forwarders. A `var`
compiles to accessors `x()` and `x_$eq(v)`, not `getX`/`setX`, so the agent's default accessor
weaving does not see Scala fields; the explicit hook does, and `-Dasynctest.agent=fields=true`
weaves the field instructions themselves. Under sbt, Jupiter needs `sbt-jupiter-interface`.

**Clojure.** No annotation syntax, so the test class is a `gen-class` and the annotations are
metadata on the method signatures. Three things that are not obvious:
- `int` annotation elements need an actual `Integer`. Clojure literals are `Long`, and
  `gen-class` hands the value to ASM unchanged, so `{:threads 8}` compiles but discovery throws
  `AnnotationTypeMismatchException ... (Found data of type java.lang.Long[8])`. Write
  `#=(int 8)`; the read-eval produces an `Integer`. `(int 8)` without `#=` is not evaluated.
- Static lifecycle methods take `^{:static true}` on the signature vector, not on the name:
  `^{:static true} [^{org.junit.jupiter.api.AfterAll {}} afterAll [] void]`. On the name it
  compiles to an instance method and JUnit refuses it.
- The namespace must be AOT-compiled (`clojure-maven-plugin` `testCompile`), and Surefire must
  include `**/*Test.class`, because the class is named by `:name`, not by the file.
`clojure.test` `deftest` cannot use `@AsyncTest`; `programmatic_runner_test.clj` runs the same
two directions through `AsyncTestRunner.run(config, body)` from plain deftests, executed by
`clojure-maven-plugin`'s `test` goal in a forked Clojure process (so `licenseMockMode` is set on
the config, not through Surefire).

## Versions

`<async-test.version>` in `pom.xml` and `asyncTestVersion` in `build.gradle.kts` are pins the
release skill bumps (`.claude/skills/release/bump-version.sh`). Language toolchain versions are
properties in the parent `pom.xml`; the Gradle files repeat them because a Gradle plugin
version cannot be read from a pom at `plugins {}` time.
