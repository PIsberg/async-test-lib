# Corpus Eval: What the Detectors Say About Code Nobody Here Wrote

_Branch: `feat/corpus-eval` - date: 2026-08-23 - produced by the standalone
[`corpus-eval/`](../../corpus-eval) module, whose gates run on every execution._

_Updated 2026-08-23 (`feat/agent-collection-weaving`): the first run of this eval found three
documented-not-thread-safe subjects producing no finding at all, and traced them to one cause: a
class that keeps its state in a JDK collection writes no field of its own, and the agent cannot
weave `java.util`. The agent's `collections=true` mode closed that gap, and the numbers below are
from the re-measurement. What changed and what it cost is stated in "What closing the gap changed"._

The [detector-accuracy eval](detector-accuracy-eval.md) measures 17 of the 142 detectors against
twins written for the test. It answers "does the analyzer's model hold", and it cannot answer the
question an evaluating team asks first: on code neither the library nor the test author wrote, does
a finding mean something is wrong, and how much noise comes with it. This document answers that on
33 classes from three third-party libraries.

## What was measured

Thirty-three classes from `commons-lang3:3.20.0`, `commons-collections4:4.5.0` and
`guava:33.4.8-jre`, each exercised by one shared instance under `@AsyncTest(threads = 6, invocations = 40)` with
`detectAll = true` and the agent attached as `fields=true,collections=true`. No detector is configured, nothing is
recorded by hand, and no line of the subject library is modified. The only thing the test body does
is call the class from six threads at once.

Ground truth is each class's own javadoc, quoted with its file and line in that library's sources
jar in [`Corpus.java`](../../corpus-eval/src/test/java/com/example/corpus/Corpus.java):

- **Nineteen classes document themselves as not thread-safe.** Sharing one instance across threads is
  the defect a user would have written, so a finding is a true positive.
- **Fourteen document themselves as safe for concurrent use.** Sharing one instance is the usage the
  class exists for, so a finding is noise.

A test method with no corpus row fails the run, so a subject cannot be exercised without a
documented contract behind it.

## Results

Measured on JDK 26 on Windows 11. Consecutive runs produce identical per-subject rows; only the
exception counts move, and only on the three subjects whose corruption surfaces as a throw.

| Subject | Contract | Findings | Detectors (tier/severity) | Crashes |
|---|---|---:|---|---:|
| `mutableInt_incrementAndGet` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `mutableLong_incrementAndGet` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `stopWatch_splitAndGet` | NOT_THREAD_SAFE | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 54 |
| `lruMap_putAndGet` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `flat3Map_putAndGet` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `listOrderedMap_putAndGet` | NOT_THREAD_SAFE | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `passiveExpiringMap_putAndGet` | NOT_THREAD_SAFE | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `arrayListMultimap_put` | NOT_THREAD_SAFE | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `evictingQueue_addAndPoll` | NOT_THREAD_SAFE | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `guavaStopwatch_startStop` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 6 |
| `statsAccumulator_add` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `hashMultimap_put` | NOT_THREAD_SAFE | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `linkedListMultimap_put` | NOT_THREAD_SAFE | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `minMaxPriorityQueue_addAndPoll` | NOT_THREAD_SAFE | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 171 |
| `hashedMap_putAndGet` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `linkedMap_putAndGet` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `multiKeyMap_putAndGet` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `caseInsensitiveMap_putAndGet` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `lazyMap_get` | NOT_THREAD_SAFE | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `fastDateFormat_format` | THREAD_SAFE | 0 | - | 0 |
| `atomicSafeInitializer_get` | THREAD_SAFE | 0 | - | 0 |
| `lazyInitializer_get` | THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `synchronizedBag_addAndCount` | THREAD_SAFE | 0 | - | 0 |
| `rateLimiter_tryAcquire` | THREAD_SAFE | 0 | - | 0 |
| `eventBus_post` | THREAD_SAFE | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `bloomFilter_putAndMightContain` | THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `atomicLongMap_incrementAndGet` | THREAD_SAFE | 0 | - | 0 |
| `concurrentHashMultiset_add` | THREAD_SAFE | 0 | - | 0 |
| `memoizedSupplier_get` | THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `joiner_join` | THREAD_SAFE | 0 | - | 0 |
| `splitter_splitToList` | THREAD_SAFE | 0 | - | 0 |
| `patternFilenameFilter_accept` | THREAD_SAFE | 0 | - | 0 |
| `fixedOrderComparator_compare` | THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |

| Measure | Value |
|---|---|
| Documented-thread-safe classes with a VERDICT-tier HIGH or CRITICAL finding | 0 of 14 |
| Documented-thread-safe classes with any finding at all | 5 of 14 |
| Documented-not-thread-safe classes with at least one finding | 19 of 19 |
| Documented-not-thread-safe classes that threw out of their own code | 3 of 19 |
| Distinct detectors that produced any finding | 2 of 142 |

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

**The misses had one cause, and it is now closed.** With field weaving alone, four of the nineteen
documented-not-thread-safe subjects report nothing: `ListOrderedMap`, `PassiveExpiringMap`,
`EvictingQueue` and `LazyMap`. All four keep their mutable state inside a JDK object behind a final
field, an `ArrayList` insert order, a `HashMap` of expiry times, an `ArrayDeque` delegate, a
decorated `HashMap`. There is no field instruction to weave, and the writes that race happen where
nothing can be woven. `LazyMap` is the useful one: it was added after the diagnosis, as a class the
explanation predicted would be missed, and it was.

The agent's `collections=true` mode rewrites the collection call itself so the instance reaches the
detectors, and all four now report. The general shape matters more than the four classes: **any
class that delegates its state to a JDK collection was invisible**, which covers most classes with a
`private final Map` or `List` field.

## What closing the gap changed

The eval's first run is the reason `collections=true` exists, so the honest way to read this
document is as a before and after. Both columns are the same 33 subjects on the same machine, with
only the agent option changed:

| Measure | Field weaving only | With collection weaving |
|---|---|---|
| Documented-not-thread-safe classes with a finding | 15 of 19 | **19 of 19** |
| Documented-thread-safe classes with a VERDICT-tier HIGH or CRITICAL finding | 0 of 14 | **0 of 14** |
| Documented-thread-safe classes with any finding | 5 of 14 | 5 of 14 |
| Total findings | 20 | 30 |

The gate that matters held: no class documented as safe for concurrent use drew a finding at the
tier a merge gate can be set to. One new `PROMPT`-tier finding did appear, on Guava's `EventBus`,
which already had one: its internal `ArrayList` and `HashMap` are written by several threads under
synchronization the weaver cannot observe. That is the cost of the reach, and it is the same cost
the tier system exists to price.

Two limits keep the mode from being noise. A collection touched only inside a `synchronized` block
reports nothing, because monitor instructions are woven alongside; `SynchronizedBag`, whose
decorator guards a plain `HashBag` with a lock this eval never declared, stays silent in both runs.
And a receiver from `java.util.concurrent` or a `Collections.synchronizedX` wrapper is never
recorded, because it synchronizes where nothing can be woven and would otherwise look unguarded at
every access.

**Two detectors of 142 produced every finding here.** `AtomicityValidator` reads the field stream,
`SharedCollectionDetector` reads the collection stream, and between them they are the detectors that
can speak about code the test does not record. The other 140 need the test body to tell them what it
did, which is what `AsyncTestContext` and the `AsyncAssert` surface are for. Nothing in this run says
those detectors are wrong; it says this corpus cannot measure them, and #300 is where that gets
classified rather than guessed at.

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
