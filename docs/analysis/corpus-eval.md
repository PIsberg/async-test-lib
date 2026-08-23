# Corpus Eval: What the Detectors Say About Code Nobody Here Wrote

_Branch: `feat/corpus-eval` - date: 2026-08-23 - produced by the standalone
[`corpus-eval/`](../../corpus-eval) module, whose gates run on every execution._

The [detector-accuracy eval](detector-accuracy-eval.md) measures 17 of the 142 detectors against
twins written for the test. It answers "does the analyzer's model hold", and it cannot answer the
question an evaluating team asks first: on code neither the library nor the test author wrote, does
a finding mean something is wrong, and how much noise comes with it. This document answers that on
19 classes from three third-party libraries.

## What was measured

Nineteen classes from `commons-lang3:3.20.0`, `commons-collections4:4.5.0` and `guava:33.4.8-jre`,
each exercised by one shared instance under `@AsyncTest(threads = 6, invocations = 40)` with
`detectAll = true` and the agent attached as `fields=true`. No detector is configured, nothing is
recorded by hand, and no line of the subject library is modified. The only thing the test body does
is call the class from six threads at once.

Ground truth is each class's own javadoc, quoted with its file and line in that library's sources
jar in [`Corpus.java`](../../corpus-eval/src/test/java/se/deversity/asynctest/corpus/Corpus.java):

- **Nine classes document themselves as not thread-safe.** Sharing one instance across threads is
  the defect a user would have written, so a finding is a true positive.
- **Ten document themselves as safe for concurrent use.** Sharing one instance is the usage the
  class exists for, so a finding is noise.

A test method with no corpus row fails the run, so a subject cannot be exercised without a
documented contract behind it.

## Results

Measured on JDK 26 on Windows 11. Three consecutive runs produced identical per-subject rows; only
the `StopWatch` exception count moved, between 45 and 53.

| Subject | Contract | Findings | Detector (tier/severity) | Threw |
|---|---|---:|---|---:|
| `MutableInt.incrementAndGet` | not thread-safe | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `MutableLong.incrementAndGet` | not thread-safe | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `StopWatch.split` | not thread-safe | 1 | AtomicityValidator (PROMPT/HIGH) | 45 to 53 |
| `LRUMap.put/get` | not thread-safe | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `Flat3Map.put/get` | not thread-safe | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `ListOrderedMap.put/get` | not thread-safe | 0 | none | 0 |
| `PassiveExpiringMap.put/get` | not thread-safe | 0 | none | 0 |
| `ArrayListMultimap.put/get` | not thread-safe | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `EvictingQueue.add/poll` | not thread-safe | 0 | none | 0 |
| `FastDateFormat.format` | thread-safe | 0 | none | 0 |
| `AtomicSafeInitializer.get` | thread-safe | 0 | none | 0 |
| `LazyInitializer.get` | thread-safe | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `SynchronizedBag.add/getCount` | thread-safe | 0 | none | 0 |
| `RateLimiter.tryAcquire` | thread-safe | 0 | none | 0 |
| `EventBus.post` | thread-safe | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `BloomFilter.put/mightContain` | thread-safe | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `AtomicLongMap.incrementAndGet` | thread-safe | 0 | none | 0 |
| `ConcurrentHashMultiset.add/count` | thread-safe | 0 | none | 0 |
| `Suppliers.memoize(...).get` | thread-safe | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |

| Measure | Value |
|---|---|
| Documented-thread-safe classes with a VERDICT-tier HIGH or CRITICAL finding | 0 of 10 |
| Documented-thread-safe classes with any finding at all | 4 of 10 |
| Documented-not-thread-safe classes with at least one finding | 6 of 9 |
| Documented-not-thread-safe classes that threw out of their own code | 1 of 9 |
| Distinct detectors that produced any finding | 1 of 142 |

## What this means for a user

**The strongest claim held.** `VERDICT` is the tier the library reserves for findings backed by a
measured case that fires on a bug and stays silent on its correctly synchronized twin, and it is
the tier `@AsyncTest(failOn = FailOn.HIGH, minTrust = TrustTier.VERDICT)` gates a merge on. Across
10 classes whose javadoc says they are safe for concurrent use, that tier produced nothing. A build
gated at `minTrust = VERDICT` would not have failed on any of them.

**Below that tier, correct lock-free code does draw findings.** Four of the ten safe classes drew a
`PROMPT`-tier HIGH finding from `AtomicityValidator`, and in every case the field it named belongs
to the library's internals rather than to the caller's code: `LazyInitializer.object`,
`LocalCache$Segment.count` and `AbstractFutureState$Waiter.next` reached through `EventBus` and the
memoizing supplier, and the `BloomFilter` bit array. These are the CAS-and-volatile idioms the
detector says it cannot model, which is what `PROMPT` means: a pattern worth a look, not a verdict.
Reading them as defects would be wrong, and a team that gates on everything will meet them.

**Six of the nine genuinely unsafe classes were caught, and one crashed.** `StopWatch` threw out of
its own code 45 to 53 times per run, which no single-threaded test would ever show. The finding
names the field and the thread count, so the report points at the state, not just at the test.

**Three misses share one cause.** `ListOrderedMap`, `PassiveExpiringMap` and `EvictingQueue` keep
their mutable state inside JDK objects behind final fields: an `ArrayList` insert order, a
`HashMap` of expiry times, an `ArrayDeque` delegate. The agent's ignore matcher excludes `java.`,
`jdk.`, `sun.` and `com.sun.`, so the writes that actually race happen where nothing can be woven,
and the detectors see no access at all. The six that fired all mutate fields they declare
themselves. This is an inference from those nine cases plus the documented ignore list rather than
a controlled experiment, and it predicts something a user will hit: **a class that delegates its
state to a JDK collection is invisible to the agent-fed detectors.**

**One detector of 142 produced every finding here.** That is the honest shape of the agent-fed
path: the weaver reports field reads, writes and monitor instructions, and the detectors that
consume exactly that stream are the ones that can speak about untouched third-party code. The rest
need the test to record what it does, which is what `AsyncTestContext` and the `AsyncAssert` surface
are for. Nothing in this run says those detectors are wrong; it says this corpus cannot measure them.

## What this does not measure

- **Nineteen classes from three libraries is not an ecosystem study.** It is enough to bound the
  false-positive rate at the tier that gates builds, and not enough to publish a rate per detector.
- **One JVM and one OS in the table above.** The gates run on whatever CI runs; the numbers quoted
  here were taken on JDK 26 on Windows 11.
- **Detection is probabilistic, and the gate reflects that.** `CorpusGates` fails the run when a
  documented-thread-safe class draws a VERDICT-tier HIGH or CRITICAL finding, and it fails when the
  unsafe group as a whole produces nothing at all. It deliberately does not assert that a particular
  subject fires on a particular run, because that would be a flaky gate rather than a measurement.
- **The corpus classes are subjects, not endorsements.** They are on the test classpath of a
  standalone module and reach neither the reactor nor any published artifact.

## Reproducing it

```bash
mvn install -DskipTests -Djacoco.skip=true    # the reactor, so the module can resolve 1.9.7
mvn -f corpus-eval/pom.xml test               # writes corpus-eval/target/corpus-eval/corpus-eval.md
```

The generated report carries the JVM, the OS, the configuration and the per-subject rows for that
run. The table in this document is a copy of one such run, not a second source of truth: when the
two disagree, the generated file is right and this document is stale.
