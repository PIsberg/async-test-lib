# Corpus Eval: What the Detectors Say About Code Nobody Here Wrote

_Produced by the standalone [`corpus-eval/`](../../corpus-eval) module, whose gates run on every
execution. Last extended 2026-08-24 for [#302](https://github.com/PIsberg/async-test-lib/issues/302):
four more libraries, eight more subjects, an exposure denominator on every rate, a control lane
with the agent detached, and a platform key on the numbers. The platform key immediately earned
itself by exposing [#316](https://github.com/PIsberg/async-test-lib/issues/316). Revised the same
day after closing the three model gaps it had left open
([#311](https://github.com/PIsberg/async-test-lib/issues/311),
[#312](https://github.com/PIsberg/async-test-lib/issues/312),
[#313](https://github.com/PIsberg/async-test-lib/issues/313)): the noise column those gaps fed is
now zero on every platform, and the numbers below are the ones that hold after all of it._

_Extended again 2026-08-29. Three changes, in the order they matter: the true-positive side is now
gated rather than only reported, the documented-safe denominator went from 23 subjects to 60, and
eight detectors this lane measures in both directions are now classified `VERDICT` on the strength
of it. The subject counts, the exposure table and the gate description below are updated to the
run that produced them, and the four-platform comparison is refilled from the `Corpus Eval` jobs on
JDK 21, 25 and 26 that ran against the change._

The [detector-accuracy eval](detector-accuracy-eval.md) measures 20 of the 146 detectors against
twins written for the test. It answers "does the analyzer's model hold", and it cannot answer the
question an evaluating team asks first: on code neither the library nor the test author wrote, does
a finding mean something is wrong, and how much noise comes with it. This document answers that on
82 subjects drawn from eight third-party libraries and the JDK.

## What was measured

Eighty-two subjects from `commons-lang3:3.20.0`, `commons-collections4:4.5.0`, `guava:33.4.8-jre`,
`jackson-databind:2.22.2`, `caffeine:3.2.4`, `netty-buffer:4.2.17.Final`, `spring-core:7.0.9`,
`HikariCP:7.0.2` and the JDK the run is on, each exercised by one shared instance under `@AsyncTest(threads = 6, invocations = 40)` with
`detectAll = true`. No detector is configured, nothing is recorded by hand, and no line of the
subject library is modified. The only thing the test body does is call the class from six threads
at once.

Every subject runs twice, in two lanes:

- **`agent-on`**, with the agent attached as `fields=true,collections=true`. Every number below
  comes from this lane.
- **`agent-off`**, with nothing attached. This is a control, not a second measurement, and what it
  controls for is stated under "Exposure" below.

Ground truth is each class's own javadoc, quoted with its file and line in that library's sources
jar in [`Corpus.java`](../../corpus-eval/src/test/java/com/example/corpus/Corpus.java):

- **Twenty-two classes document themselves as not thread-safe.** Sharing one instance across
  threads is the defect a user would have written, so a finding is a true positive.
- **Sixty document themselves as safe for concurrent use.** Sharing one instance is the usage the
  class exists for, so a finding is noise.

The safe side is the larger half on purpose, and it was not always. A zero over 22 documented-safe
classes bounds the false-positive rate near 13% at 95% confidence, because the rule of three sets
that bound from the size of the denominator and not from the length of the run of zeroes. Sixty
subjects put it at 5.0%. Every one of the 38 added was picked for shared mutable state behind a
real mechanism - striped locks, copy-on-write, a synchronized decorator, a monitor the caller is
told to hold - and never for being trivially safe, because a stateless utility class enlarges the
denominator without ever having been able to draw a finding. That buys a smaller number and not a
stronger claim.

A test method with no corpus row fails the run, so a subject cannot be exercised without a
documented contract behind it. A class that states nothing is not a subject however obvious its
behaviour looks, which is why the corpus carries Netty's `PooledByteBufAllocator` and none of its
buffers: `ByteBufAllocator` documents its implementations as thread-safe, and no `ByteBuf`
implementation documents anything. Putting both in one "Netty" bucket would have diluted the
documented-safe denominator with classes whose ground truth was inferred.

## Exposure: the denominator, before any rate

The first version of this document reported a per-class rate over 15 classes and no per-detector
rate at all, because there was nothing to divide by. A finding count on its own cannot tell "no
false positive from detector X" apart from "X never ran", and in this corpus the second is true of
almost every detector.

A detector is fed by one of three things, classified per type in
[`DetectorFeeds`](../../async-test-lib/src/main/java/se/deversity/asynctest/diagnostics/DetectorFeeds.java)
and listed in [DETECTOR_CATALOG.md](../DETECTOR_CATALOG.md#what-feeds-each-detector):

| Feed | Detectors | Fed in `agent-on` | Fed in `agent-off` |
|---|---:|---|---|
| `AGENT` | 18 | yes, by the woven field and collection streams | no, there are no woven streams |
| `ZERO_CONFIG` | 3 | yes, by `ThreadMXBean`, thread dumps and the runner | yes, the same |
| `RECORDING` | 125 | no, nothing here calls a `record*` API | no, the same |

So the attached lane exposes 21 detectors of 146 and the control lane 3. Two of the eighteen
agent-fed produce every finding this eval has recorded, on every platform. The other sixteen model
locks, latches, date formats and builders, and a corpus whose entire test body is "share one
instance and call it" never writes those idioms down for them to see. Their silence is correct,
which is why the detection gate names the two and not the eighteen. That is the denominator
for everything below, and it is checked rather than asserted: `CorpusGates` fails the run if a
detector the feed table says cannot be fed reports anyway, and fails the control lane if either
agent-fed detector is heard from at all. The control lane's measured result is zero findings from
zero exposed agent-fed detectors, which is what makes the attached lane's findings attributable to
the agent rather than to the harness.

Per exposed detector, over the 60 documented-safe and 22 documented-unsafe subjects, from run **L**
below:

| Detector | Feed | Safe exposed | ...with a finding | Unsafe exposed | ...with a finding |
|---|---|---:|---:|---:|---:|
| `AtomicityValidator` | AGENT | 60 | 0 | 22 | 16 |
| `SharedCollectionDetector` | AGENT | 60 | 0 | 22 | 14 |
| `LockOrderValidator` | AGENT | 60 | 0 | 22 | 0 |
| `SemaphoreMisuseDetector` | AGENT | 60 | 0 | 22 | 0 |
| `LockLeakDetector` | AGENT | 60 | 0 | 22 | 0 |
| `BlockingQueueDetector` | AGENT | 60 | 0 | 22 | 0 |
| `SimpleDateFormatDetector` | AGENT | 60 | 0 | 22 | 0 |
| `CountDownLatchDetector` | AGENT | 60 | 0 | 22 | 0 |
| `SleepInLockDetector` | AGENT | 60 | 0 | 22 | 0 |
| `CalendarDetector` | AGENT | 60 | 0 | 22 | 0 |
| `StringBuilderDetector` | AGENT | 60 | 0 | 22 | 0 |
| `SharedFormatterDetector` | AGENT | 60 | 0 | 22 | 0 |
| `SharedMatcherDetector` | AGENT | 60 | 0 | 22 | 0 |
| `SharedDecimalFormatDetector` | AGENT | 60 | 0 | 22 | 0 |
| `SharedMessageDigestDetector` | AGENT | 60 | 0 | 22 | 0 |
| `ExplicitGcDetector` | AGENT | 60 | 0 | 22 | 0 |
| `TryLockMisuseDetector` | AGENT | 60 | 0 | 22 | 0 |
| `LatchMisuseDetector` | AGENT | 60 | 0 | 22 | 0 |
| `DeadlockDetector` | ZERO_CONFIG | 60 | 0 | 22 | 0 |
| `LivelockDetector` | ZERO_CONFIG | 60 | 0 | 22 | 0 |
| `StaticInitDeadlockDetector` | ZERO_CONFIG | 60 | 0 | 22 | 0 |

The sixteen agent-fed zeroes are not sixteen failures. `StringBuilderDetector` is the clearest
case: it is exposed on all 82 subjects, one of which is a `StringBuffer` shared across six threads,
and it correctly says nothing, because the class it models is the other one. The rest model locks,
latches, date formats and matchers, and no subject here writes those idioms down. The detection
gate names the two detectors this corpus actually exercises for exactly that reason: requiring all
eighteen to fire would fail on correct silence.

The three zero-config rows are the ones worth reading twice. They are exposed on all 82 subjects
and reported nothing, which is a measured zero: none of these subjects deadlocks, livelocks or
parks in a class initializer, and the detectors that would have said so were running. The 125
recording-fed detectors have no row, because a rate over an exposure of zero is not a rate.

## Results

Every number in this section is keyed to the run it came from. The agent, the memory model and the
scheduler all differ across JDK releases, so a table that quotes one number and names no platform
is not reproducible.

| Key | JDK | OS | Agent | Source |
|---|---|---|---|---|
| **L** | 26 (Temurin) | Windows 11 26200 (amd64) | `fields=true,collections=true` | local run, 2026-08-24 |
| **C21** | 21 (Temurin) | ubuntu-latest | `fields=true,collections=true` | corpus workflow, `Corpus Eval (Java 21)` |
| **C25** | 25 (Temurin) | ubuntu-latest | `fields=true,collections=true` | corpus workflow, `Corpus Eval (Java 25)` |
| **C26** | 26 (Temurin) | ubuntu-latest | `fields=true,collections=true` | corpus workflow, `Corpus Eval (Java 26)` |
| **L-off** / **C-off** | as above | as above | not attached | the control lane of the same run |

Each CI job uploads both lanes' reports as `corpus-eval-report-java-<version>`, so any row here
can be checked against the run that produced it.

### The platforms used to disagree; what that turned out to be

The first run of this table did what a platform key is for. The three CI legs agreed with each
other and disagreed with a local run in both directions: 20 of 20 documented-unsafe subjects
detected locally against 7, 7 and 6 on CI, and 3 of 22 documented-safe subjects with a finding
locally against 6 on all three CI legs. The single-run version of this document would have
published the local numbers as properties of the library.

The cause was not the operating system, the JDK, the core count, the telemetry pipeline or the
lock model, all of which were tested and ruled out
([#316](https://github.com/PIsberg/async-test-lib/issues/316) records the eleven experiments). It
was **which test class Surefire ran first.** The library self-attaches its agent from the first
`@AsyncTest`; retransformation covers the classes loaded at that instant and load-time weaving
covers everything after, and a class that falls between the two is woven by neither with nothing
logged. This module builds its subjects in the test class's field initializers, so they land on
different sides of that line depending on execution order, and the order differs between a
developer machine and a CI runner. One flag reproduces a CI result on a developer machine:

```bash
mvn -f corpus-eval/pom.xml test -Dsurefire.runOrder=reversealphabetical
```

| Subject | default order | reversed | CI, before the fix |
|---|---:|---:|---:|
| `mutableInt_incrementAndGet` | 1,744 ev / 1 | 671 ev / 0 | 671 ev / 0 |
| `arrayListMultimap_put` | 2,061 ev / 2 | 911 ev / 0 | 921 ev / 0 |
| `rateLimiter_tryAcquire` | 6,471 ev / 0 | 4,815 ev / 1 | 4,815 ev / 1 |
| documented-unsafe detected | 20 of 20 | 6 of 20 | 7 of 20 |

The attached lane now attaches with `-javaagent:` at JVM startup, so `premain` weaves before any
subject class exists and nothing depends on file order. `CorpusGates` fails the run if that
changes. The agent-side defect this works around is
[#321](https://github.com/PIsberg/async-test-lib/issues/321): a user who self-attaches to an
existing suite can still lose classes the same way.

### What the platforms agree on now

| Measure | **L** | **C21** | **C25** | **C26** |
|---|---:|---:|---:|---:|
| Documented-thread-safe with a VERDICT-tier HIGH or CRITICAL finding | **0 of 60** | **0 of 60** | **0 of 60** | **0 of 60** |
| Documented-not-thread-safe with at least one finding | **22 of 22** | **22 of 22** | **22 of 22** | **22 of 22** |
| Documented-thread-safe with any finding at all | **0 of 60** | **0 of 60** | **0 of 60** | **0 of 60** |
| `AtomicityValidator`, documented-unsafe subjects with a finding | 16 of 22 | 16 of 22 | 16 of 22 | 16 of 22 |
| `SharedCollectionDetector`, documented-unsafe subjects with a finding | 14 of 22 | 14 of 22 | 14 of 22 | 14 of 22 |
| `mutableInt_incrementAndGet`, events / findings | 1,439 / 1 | 1,455 / 1 | 1,455 / 1 | 1,455 / 1 |
| `rateLimiter_tryAcquire`, events / findings | 6,431 / 0 | 6,431 / 0 | 6,431 / 0 | 6,431 / 0 |
| Telemetry events dropped | 0 | 0 | 0 | 0 |

Every row is identical on all four platforms, over the corpus as it now stands: the same 22
subjects detected, the same zero noise over a safe side that is now 60 wide, the same per-detector
split, and per-subject event counts that agree to within 1.1%. The thirty-seven documented-safe
subjects added in 2026-08-29 hold at zero on Linux and on JDK 21 and 25, not only on the machine
they were written on - which is the question a single-machine zero cannot answer, and the reason
this table has a platform key at all. The rows that used to differ were
the corpus doing its job. `objectMapper_configuredThenShared` drew findings on the CI legs only,
and the reason was physical: on two cores a lost cache fill surfaces as the next round's re-miss
and re-write, so jackson's serializer caches warm over two rounds there and one round on six
local cores, and its `PrivateMaxEntriesMap.entrySet` view is raced on CI's schedule and not on
the local one. Both are settled-cache forms the
[#313](https://github.com/PIsberg/async-test-lib/issues/313) rules now recognise, and closing
them needed the CI evidence: three local runs in a row had shown a zero this document could not
yet claim for any other machine.

### Divergences to expect, and how to read one

A cell that differs between platforms is not automatically noise. Two differences are predicted by
the library rather than observed by it, and are declared here so a reader does not have to guess:

- **Virtual-thread pinning is version-dependent.** `VirtualThreadPinningDetector` treats JDK 24 and
  later differently, because JEP 491 stopped `synchronized` pinning a carrier, and JDK 26 again
  differently for class-initialization pinning. A pinning row that reads differently on 21 than on
  25 or 26 is the detector being correct on both, not variance. It cannot appear in this table
  today: that detector is recording-fed and its exposure here is zero, so it becomes live only if a
  recording lane is added ([#310](https://github.com/PIsberg/async-test-lib/issues/310)).
- **Crash counts move, findings do not.** Corruption in a documented-unsafe subject can surface as
  a thrown exception instead of as a finding, and which subjects do that, and how often, changes
  with the scheduler. `stopWatch_splitAndGet`, `guavaStopwatch_startStop` and
  `minMaxPriorityQueue_addAndPoll` throw on most runs; the commons maps throw occasionally. That
  column is a symptom, not a measurement, and the gate does not read it per subject.
- **One unsafe cell moved with the rules, and stays moved.** `hashMultimap_put` drew two
  findings before (`AtomicityValidator` and `SharedCollectionDetector`) and draws one now:
  guava's lazy array allocation is a miss-checked one-shot write the run then outlives, which is
  the shape the settled single-check rule excuses, so the atomicity half stays home while
  `SharedCollectionDetector` keeps the subject detected in every run, on every platform. While
  the rules were being built the cell was briefly probabilistic — one local run of six read 1
  finding while the construction excuse alone caught the allocation only when the allocating
  thread won outright — and the run-clock settle is what made the outcome deterministic. The
  row never goes to zero and the group gate never depended on it.

Anything else that differs between two cells is worth an issue. That is not a figure of speech:
the first cross-platform run of this table produced a difference nobody predicted, and it was a
real defect rather than variance ([#316](https://github.com/PIsberg/async-test-lib/issues/316)).

### Per subject, run **L** (local, JDK 26 on Windows 11)

| Subject | Library | Contract | Events | Findings | Detectors (tier/severity) | Crashes |
|---|---|---|---:|---:|---|---:|
| `mutableInt_incrementAndGet` | commons-lang3:3.20.0 | NOT_THREAD_SAFE | 1439 | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `mutableLong_incrementAndGet` | commons-lang3:3.20.0 | NOT_THREAD_SAFE | 1439 | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `stopWatch_splitAndGet` | commons-lang3:3.20.0 | NOT_THREAD_SAFE | 5667 | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 66 |
| `lruMap_putAndGet` | commons-collections4:4.5.0 | NOT_THREAD_SAFE | 5409 | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `flat3Map_putAndGet` | commons-collections4:4.5.0 | NOT_THREAD_SAFE | 4559 | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `listOrderedMap_putAndGet` | commons-collections4:4.5.0 | NOT_THREAD_SAFE | 1670 | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `passiveExpiringMap_putAndGet` | commons-collections4:4.5.0 | NOT_THREAD_SAFE | 3119 | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `arrayListMultimap_put` | guava:33.4.8-jre | NOT_THREAD_SAFE | 4177 | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `evictingQueue_addAndPoll` | guava:33.4.8-jre | NOT_THREAD_SAFE | 2159 | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `guavaStopwatch_startStop` | guava:33.4.8-jre | NOT_THREAD_SAFE | 2391 | 1 | AtomicityValidator (PROMPT/HIGH) | 9 |
| `statsAccumulator_add` | guava:33.4.8-jre | NOT_THREAD_SAFE | 4269 | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `hashMultimap_put` | guava:33.4.8-jre | NOT_THREAD_SAFE | 1459 | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `linkedListMultimap_put` | guava:33.4.8-jre | NOT_THREAD_SAFE | 4571 | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `minMaxPriorityQueue_addAndPoll` | guava:33.4.8-jre | NOT_THREAD_SAFE | 16337 | 1 | AtomicityValidator (PROMPT/HIGH) | 70 |
| `hashedMap_putAndGet` | commons-collections4:4.5.0 | NOT_THREAD_SAFE | 3893 | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `linkedMap_putAndGet` | commons-collections4:4.5.0 | NOT_THREAD_SAFE | 3913 | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `multiKeyMap_putAndGet` | commons-collections4:4.5.0 | NOT_THREAD_SAFE | 7913 | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `caseInsensitiveMap_putAndGet` | commons-collections4:4.5.0 | NOT_THREAD_SAFE | 3893 | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `lazyMap_get` | commons-collections4:4.5.0 | NOT_THREAD_SAFE | 1203 | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `fastDateFormat_format` | commons-lang3:3.20.0 | THREAD_SAFE | 4511 | 0 | - | 0 |
| `atomicSafeInitializer_get` | commons-lang3:3.20.0 | THREAD_SAFE | 1160 | 0 | - | 0 |
| `lazyInitializer_get` | commons-lang3:3.20.0 | THREAD_SAFE | 1162 | 0 | - | 0 |
| `synchronizedBag_addAndCount` | commons-collections4:4.5.0 | THREAD_SAFE | 4030 | 0 | - | 0 |
| `rateLimiter_tryAcquire` | guava:33.4.8-jre | THREAD_SAFE | 6431 | 0 | - | 0 |
| `eventBus_post` | guava:33.4.8-jre | THREAD_SAFE | 31366 | 0 | - | 0 |
| `bloomFilter_putAndMightContain` | guava:33.4.8-jre | THREAD_SAFE | 27844 | 0 | - | 0 |
| `atomicLongMap_incrementAndGet` | guava:33.4.8-jre | THREAD_SAFE | 907 | 0 | - | 0 |
| `sequenceWriter_write` | jackson-databind:2.22.2 | NOT_THREAD_SAFE | 25299 | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 239 |
| `hashBasedTable_put` | guava:33.4.8-jre | NOT_THREAD_SAFE | 3848 | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `guavaLoadingCache_get` | guava:33.4.8-jre | THREAD_SAFE | 10604 | 0 | - | 0 |
| `concurrentHashMultiset_add` | guava:33.4.8-jre | THREAD_SAFE | 1395 | 0 | - | 0 |
| `memoizedSupplier_get` | guava:33.4.8-jre | THREAD_SAFE | 1409 | 0 | - | 0 |
| `joiner_join` | guava:33.4.8-jre | THREAD_SAFE | 4041 | 0 | - | 0 |
| `splitter_splitToList` | guava:33.4.8-jre | THREAD_SAFE | 33325 | 0 | - | 0 |
| `patternFilenameFilter_accept` | guava:33.4.8-jre | THREAD_SAFE | 911 | 0 | - | 0 |
| `fixedOrderComparator_compare` | commons-collections4:4.5.0 | THREAD_SAFE | 1391 | 0 | - | 0 |
| `fileBackedOutputStream_writeAndReset` | guava:33.4.8-jre | THREAD_SAFE | 3551 | 0 | - | 0 |
| `objectMapper_reconfigureWhileWriting` | jackson-databind:2.22.2 | NOT_THREAD_SAFE | 119129 | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `objectMapper_configuredThenShared` | jackson-databind:2.22.2 | THREAD_SAFE | 112905 | 0 | - | 0 |
| `objectReader_readValue` | jackson-databind:2.22.2 | THREAD_SAFE | 75562 | 0 | - | 0 |
| `objectWriter_writeValueAsString` | jackson-databind:2.22.2 | THREAD_SAFE | 82306 | 0 | - | 0 |
| `caffeineCache_getAndPut` | caffeine:3.2.4 | THREAD_SAFE | 11173 | 0 | - | 0 |
| `caffeineAsMap_computeIfAbsent` | caffeine:3.2.4 | THREAD_SAFE | 3464 | 0 | - | 0 |
| `pooledByteBufAllocator_bufferAndRelease` | netty-buffer:4.2.17.Final | THREAD_SAFE | 62814 | 0 | - | 0 |
| `concurrentReferenceHashMap_putAndGet` | spring-core:7.0.9 | THREAD_SAFE | 10304 | 0 | - | 0 |
| `concurrentHashMap_putAndGet` | jdk:26 | THREAD_SAFE | 901 | 0 | - | 0 |
| `copyOnWriteArrayList_addAndIterate` | jdk:26 | THREAD_SAFE | 911 | 0 | - | 0 |
| `stringBuffer_appendAndLength` | jdk:26 | THREAD_SAFE | 901 | 0 | - | 0 |
| `concurrentLinkedQueue_addAndPoll` | jdk:26 | THREAD_SAFE | 911 | 0 | - | 0 |
| `linkedBlockingQueue_offerAndPoll` | jdk:26 | THREAD_SAFE | 921 | 0 | - | 0 |
| `hashtable_putAndGet` | jdk:26 | THREAD_SAFE | 911 | 0 | - | 0 |
| `concurrentSkipListMap_putAndGet` | jdk:26 | THREAD_SAFE | 911 | 0 | - | 0 |
| `synchronizedList_addUnderItsMonitor` | jdk:26 | THREAD_SAFE | 1151 | 0 | - | 0 |
| `threadLocalRandom_nextInt` | jdk:26 | THREAD_SAFE | 431 | 0 | - | 0 |
| `atomicInteger_incrementAndGet` | jdk:26 | THREAD_SAFE | 671 | 0 | - | 0 |
| `thresholdCircuitBreaker_incrementAndCheckState` | commons-lang3:3.20.0 | THREAD_SAFE | 1861 | 0 | - | 0 |
| `eventCountCircuitBreaker_incrementAndCheckState` | commons-lang3:3.20.0 | THREAD_SAFE | 4227 | 0 | - | 0 |
| `memoizer_compute` | commons-lang3:3.20.0 | THREAD_SAFE | 1151 | 0 | - | 0 |
| `constantInitializer_get` | commons-lang3:3.20.0 | THREAD_SAFE | 911 | 0 | - | 0 |
| `atomicInitializer_get` | commons-lang3:3.20.0 | THREAD_SAFE | 1168 | 0 | - | 0 |
| `range_contains` | commons-lang3:3.20.0 | THREAD_SAFE | 1631 | 0 | - | 0 |
| `staticBucketMap_putAndGet` | commons-collections4:4.5.0 | THREAD_SAFE | 3554 | 0 | - | 0 |
| `commonsReferenceHashMap_putAndGet` | commons-collections4:4.5.0 | THREAD_SAFE | 7877 | 0 | - | 0 |
| `synchronizedCollection_addAndSize` | commons-collections4:4.5.0 | THREAD_SAFE | 1871 | 0 | - | 0 |
| `synchronizedSortedBag_addAndCount` | commons-collections4:4.5.0 | THREAD_SAFE | 4270 | 0 | - | 0 |
| `synchronizedMultiSet_addAndCount` | commons-collections4:4.5.0 | THREAD_SAFE | 4259 | 0 | - | 0 |
| `synchronizedQueue_addAndPoll` | commons-collections4:4.5.0 | THREAD_SAFE | 2041 | 0 | - | 0 |
| `strongInterner_intern` | guava:33.4.8-jre | THREAD_SAFE | 3658 | 0 | - | 0 |
| `weakInterner_intern` | guava:33.4.8-jre | THREAD_SAFE | 3181 | 0 | - | 0 |
| `guavaSynchronizedQueue_addAndPoll` | guava:33.4.8-jre | THREAD_SAFE | 1871 | 0 | - | 0 |
| `guavaSynchronizedDeque_addAndPoll` | guava:33.4.8-jre | THREAD_SAFE | 1871 | 0 | - | 0 |
| `synchronizedTable_putAndGet` | guava:33.4.8-jre | THREAD_SAFE | 4755 | 0 | - | 0 |
| `concurrentHashSet_addAndContains` | guava:33.4.8-jre | THREAD_SAFE | 911 | 0 | - | 0 |
| `hashFunction_hashString` | guava:33.4.8-jre | THREAD_SAFE | 3071 | 0 | - | 0 |
| `mapMakerMap_putAndGet` | guava:33.4.8-jre | THREAD_SAFE | 911 | 0 | - | 0 |
| `synchronizedSupplier_get` | guava:33.4.8-jre | THREAD_SAFE | 2195 | 0 | - | 0 |
| `guavaCache_getAndPut` | guava:33.4.8-jre | THREAD_SAFE | 29704 | 0 | - | 0 |
| `asyncCache_getAndJoin` | caffeine:3.2.4 | THREAD_SAFE | 4180 | 0 | - | 0 |
| `asyncLoadingCache_getAndJoin` | caffeine:3.2.4 | THREAD_SAFE | 4324 | 0 | - | 0 |
| `caffeineLoadingCache_get` | caffeine:3.2.4 | THREAD_SAFE | 3793 | 0 | - | 0 |
| `unpooledByteBufAllocator_bufferAndRelease` | netty-buffer:4.2.17.Final | THREAD_SAFE | 9086 | 0 | - | 0 |
| `conversionService_convert` | spring-core:7.0.9 | THREAD_SAFE | 7890 | 0 | - | 0 |

| Measure | **L** | **L-off** |
|---|---|---|
| Detectors exposed at all | 21 of 146 | 3 of 146 |
| Documented-thread-safe classes with a VERDICT-tier HIGH or CRITICAL finding | 0 of 60 | 0 of 60 |
| Documented-thread-safe classes with any finding at all | **0 of 60** | 0 of 60 |
| Documented-not-thread-safe classes with at least one finding | 22 of 22 | 0 of 22 |
| Documented-not-thread-safe classes that threw out of their own code | 4 of 22 | 4 of 22 |
| Distinct detectors that produced any finding | 2 of 21 exposed | 0 of 3 exposed |

The control column is the same on every platform: with nothing attached, all four runs observed
nothing at all from any agent-fed detector, over all 82 subjects. Whatever moves between machines moves
inside the woven pipeline, not in the harness.

## What this means for a user

**The strongest claim held, on every platform.** `VERDICT` is the tier the library reserves for
findings backed by a measured case that fires on a bug and stays silent on its correctly
synchronized twin, and it is the tier `@AsyncTest(failOn = FailOn.HIGH, minTrust =
TrustTier.VERDICT)` gates a merge on. Across 22 classes whose javadoc says they are safe for
concurrent use, no finding reached it on any of the four runs: a build gated at that tier would not
have failed on any of them, anywhere. This is the one result the cross-platform comparison leaves
standing, and it is the result the tier system was built to produce.

**Below the gate, the noise column is now empty.** Three classes drew a `PROMPT`-tier finding on
every platform, and a fourth on CI only; all four were the same three model gaps, and the
section below records the rules that closed them. Zero of the twenty-two documented-safe classes
now draw any finding at all, on any of the four runs this document keys.

**What attaching the agent buys, in one number.** Twenty-one detectors of 146 could see anything in
this corpus, and two of them produced every finding in it, on every platform. That is not a defect
in the other 144: 125 of them are told what happened by the test body, and this corpus tells them
nothing on purpose, while sixteen of the agent-fed eighteen model idioms no subject here writes.
A user attaching the agent to an existing suite and changing no test code is buying the
`AGENT` set; a user willing to record is buying the rest. The control lane is what makes that
concrete: with the agent detached the same 82 subjects produced zero findings, so nothing in the
attached lane's column came from the harness.

## The three findings on documented-thread-safe code, and the rules that closed them

Each was traced to what the model could not see, published here as an open issue, and then closed
by giving the analyzer the missing rule. None was tuned away: every rule names an idiom, ships a
twin pair in both directions in `DetectorAccuracyEvalTest`, and was verified to silence its
subject while the full unsafe group stayed detected.

**`ConcurrentReferenceHashMap$Segment.resizeThreshold` (spring-core), closed by
[#311](https://github.com/PIsberg/async-test-lib/issues/311).** The field is written under the
segment's own lock and read once without it, at `ConcurrentReferenceHashMap.java:724`, as a hint
for whether a restructure is likely; the authoritative check is re-made under the lock at line
747 before anything acts on it. The rule: when every write to a field holds a consistent lock
and the same thread, in the same round, demonstrably re-reads the field under that lock after an
unlocked read, the unlocked reads are hints. The demonstration is asked for once per instance
rather than after every hint, because the paths that decide "do nothing" leave no trace. The
finding stands when the unlocked read is never re-established under the write lock, and when the
later read is under a lock the writes do not hold.

**`LongLongHashMap.mask` and `.maxProbe` (netty-buffer), closed by
[#312](https://github.com/PIsberg/async-test-lib/issues/312).** Netty builds a pool chunk's
metadata while holding the arena's lock and then serves that chunk under the chunk's own
`runsAvailLock`, so the lockset covering the construction-time writes and the lockset covering
every later read do not intersect. The rule is the Eraser initialization state, kept per
receiver: an access made while the receiver is still exclusive to the thread that built it is
construction, not contention, and leaves the contention stats. The excuse must first be
corroborated: the receiver's post-publication accesses have to span more than one
harness-ordered round, so a builder and a reader inside a single round keep reporting exactly
as they always did. The state flips permanently at the first access from any other thread, so a
write to an object that has already escaped is never excused, which is the boundary
`ThisEscapeDetector` owns.

**`MapSerializer._dynamicValueSerializers`, `StdKeySerializers$Dynamic._dynamicSerializers` and
`PrivateMaxEntriesMap.entrySet` (jackson-databind), closed by
[#313](https://github.com/PIsberg/async-test-lib/issues/313).** Jackson's serializer lookup is a
racy single-check cache: read a non-volatile reference, and on a miss compute a fresh immutable
map and store it. Threads can lose each other's writes, and that is fine, because recomputation
is idempotent and the stored value is immutable. Immutability and idempotence are not observable
from an access stream, so the rule keys on what a cache does that a genuine lost update cannot:
it settles. Writes confined to a warming prefix of rounds — a lost fill surfaces as the next
round's re-miss and re-write, so a slow scheduler warms in more than one round, though never in
more rounds than there are writers — every store preceded by that thread's miss-check read, and
then a settled suffix of multi-thread reads at least as long as the warming and never shorter
than two rounds, with no write. A counter or a lossy copy-on-write structure keeps writing and
can never out-settle its own warming, and a run too short to show convergence keeps its finding.
One shape needed the run to answer instead of the field: jackson's
`PrivateMaxEntriesMap.entrySet` is a lazily created view raced once during warmup and never
touched again — the serializer cache rebuilds a read-only snapshot and the backing map sleeps —
so it has no settled reads to show. The run does: rounds are harness-ordered, so a run that kept
executing for the required rounds after the field's last write, with the field demonstrably
never raced again, is the same convergence on the only clock left, while a race in the closing
rounds earns nothing and keeps its finding. What the rule deliberately does not judge is whether
the stored value was safe to publish unsafely: a torn or stale value is visibility, not
atomicity, and stays `ConstructorSafetyValidator` and `VisibilityMonitor` business.

**The blind spot that rule left, and what closed it
([#326](https://github.com/PIsberg/async-test-lib/issues/326)).** The settle rule reads
convergence off the field, and convergence is a property of the field. A double-submit shaped
like a view cache - `if (job == null) job = submit()` - produces the same access stream as
Jackson's serializer cache: the same miss checks, the same racing stores, the same settled
suffix of reads. It is excused, and the work was done twice. Idempotence and immutability are
invisible in a stream that carries no values, so the shapes could not be separated at all.

The weaver now captures one more thing for a reference store: the identity of the value it put
in the field. A reference store is the one shape where the value is already on the operand stack
in argument order, so `DUP2` reaches it in two instructions with nothing to undo and no scratch
local, which keeps the frames the weaver deliberately does not recompute valid. With that, the
excuse asks a second question - did the published object then go quiet - and answers it from the
stream it already has, because every write already carries the identity of the object it belongs
to. An effectively immutable value is written once and read from then on, which is what the JMM's
final-field guarantee promises statically; a live job keeps writing.

**Absence of evidence keeps the previous answer.** A stored identity of 0 - a primitive write, a
shape the weaver could not reach, an older agent, or a payload of a type the agent does not weave,
which includes every JDK class - means nothing is known, and nothing known must not become a
finding. The rule only ever narrows, and only where there is something to narrow with. The corpus
is the proof that it did not overreach: all twenty-two documented-safe subjects stayed silent and
all twenty documented-unsafe ones stayed detected with the rule in place.

Note which Jackson subjects stayed silent all along: `ObjectMapper` configured once and then
shared, and `ObjectReader`. The mutant-factory contract that Jackson documents most strongly is
the one the model reads correctly.

## What the corpus taught the model, in five rounds

Every rule below was added because a documented contract disagreed with a finding, was traced to
something the model did not know, and generalises beyond the subject that exposed it. Detection
stayed at the full unsafe group through all of them, which is the number that matters while
chasing the noise column: a rule that quietens a false positive by weakening detection has not
fixed anything.

**Round one: reach.** Field weaving alone saw 15 of the 19 documented-unsafe classes of the time,
because a class that keeps its state in a JDK collection writes no field of its own and the agent
cannot weave `java.util`. The agent's `collections=true` mode rewrote the collection call sites and
closed that gap.

**Round two: precision.** With reach in place the noise column was measured at 2 of 15, and each
finding was traced to a model gap rather than tuned away: `synchronized` methods compile to
`ACC_SYNCHRONIZED` and no monitor instruction, so every woven access now carries its receiver and
the monitor of an enclosing `synchronized` method; locksets are intersected rather than compared
for equality; the accessor `Advice` stands down when field instructions are woven; a receiver is
recorded only when it inherits instance fields from a bootstrap-loaded class; and threads are
counted per round rather than across all rounds.

**Round three: `MemberSubstitution`.** Replacing Byte Buddy's `MemberSubstitution` with the
library's own call-site visitor did more than let records weave. Its relaxed mode silently skips a
call site whose method graph fails to resolve, and four documented-unsafe commons maps turned out
to have been skipped that way all along.

**Round four: retransformation was all-or-nothing.** Adding four libraries to the corpus classpath
dropped detection from the full unsafe group to zero, and the cause was neither the subjects nor
the detectors. Byte Buddy's default hands every already-loaded class to
`Instrumentation.retransformClasses` in one call, and that call is all-or-nothing; the default
`RedefinitionStrategy.Listener` then swallows the failure. Netty ships optional logger adapters
(`io.netty.util.internal.logging.Log4JLogger`, `Slf4JLoggerFactory$NopInstanceHolder`) that the JVM
refuses to re-weave with `java.lang.InternalError: class redefinition failed: invalid class`, and
those two classes cost every other class its weaving:

| Corpus classpath | Classes instrumented | Documented-unsafe classes detected |
|---|---:|---|
| three libraries | 1074 | 19 of 19 |
| seven libraries, before the fix | 200 | 0 of 20 |
| seven libraries, after the fix | 1803 | **20 of 20** |

The agent now retransforms in fixed batches, halves a failing batch until the offending type stands
alone, and names that type on stderr. This was never a corpus problem: any user attaching the agent
to a suite with a class the JVM will not re-weave was silently getting "weave only what loads from
here on", with detection quietly reduced and nothing said.

**Round five: the last three, closed as rules.** The three findings the previous revision of this
document traced and left open are now rules the analyzer applies: the locked re-read
([#311](https://github.com/PIsberg/async-test-lib/issues/311)), the corroborated construction
write ([#312](https://github.com/PIsberg/async-test-lib/issues/312)) and the settled single-check
cache ([#313](https://github.com/PIsberg/async-test-lib/issues/313)), each shipped with twins in
both directions in `DetectorAccuracyEvalTest`. The noise column went from 3 of 22 locally and 4
of 22 on CI to 0 of 22 on all four platforms, with the unsafe group still detected in full. The
round also demonstrated why the platform key exists twice over: the settle rule's first form
passed three consecutive local runs and failed on every CI leg, because a two-core schedule
spreads a cache's warm-up over two rounds and surfaces races a six-core machine never hits. One
unsafe cell moved with the rules and is declared under "Divergences to expect":
`hashMultimap_put` keeps its `SharedCollectionDetector` finding and loses the
`AtomicityValidator` one, guava's lazy array allocation being the same miss-checked one-shot
shape the settled single-check rule excuses.

## What this does not measure

- **Eighty-two subjects from eight libraries and the JDK is not an ecosystem study.** It bounds
  the false-positive rate at the tier that gates builds, over a stated denominator, and it does
  not support a claim about the JVM ecosystem. Sixty documented-safe subjects put that bound at
  5.0%, which is a useful number and not a vanishing one.
- **125 detectors of 146 are not measured in the unmodified lanes at all.** Their exposure there
  is zero, so those lanes say nothing about them in either direction. Twelve are measured in the
  recording lane below, which is a different eval over a different denominator and is reported
  separately for that reason.
- **A noise column of zero is a measurement, not a guarantee.** All sixty documented-safe
  classes produce nothing, and each of the last three went quiet because a specific idiom became
  a rule the analyzer can check, never because a threshold moved. A correct class guarded by a
  mechanism the weaver cannot see, a lock acquired inside unwoven code, a hand-rolled protocol on
  plain fields, will still draw a `PROMPT`-tier finding, and the tier system exists to price
  exactly that. This corpus no longer contains such a class; the next library someone points the
  agent at may.
- **Detection is probabilistic, and the gate is built around that rather than excused by it.**
  `CorpusGates` fails the run when a documented-thread-safe class draws a VERDICT-tier HIGH or
  CRITICAL finding, when fewer than 85% of the documented-unsafe subjects draw a finding, when
  either `AtomicityValidator` or `SharedCollectionDetector` says nothing about any of them, when a
  detector reports that the feed table says cannot be fed, and when the control lane hears from an
  agent-fed detector. It still does not assert that a particular subject fires on a particular run,
  because that would be a flaky gate rather than a measurement.

  The detection half of that list is new, and what it replaced is worth recording. The gate used to
  pass on one finding *or one crash* anywhere in the unsafe group, and four of those subjects
  throw on most runs, so the crash half satisfied it alone: both detectors could have gone silent
  on all twenty-two subjects and the table above would still have been published green. Filtering
  `SharedCollectionDetector` out of the findings before the gate drops detection to 16 of 22 and
  now fails the floor of 18; the old gate stayed green on the same input. Crashes no longer count
  towards detection at all.
- **The corpus classes are subjects, not endorsements.** They are on the test classpath of a
  standalone module and reach neither the reactor nor any published artifact.

## The recording lane: a denominator for detectors the corpus cannot reach

The bullet above used to end this document's account of the 125: exposure zero, nothing said in
either direction. That is now measured for seventy-six of them, in its own lane, and the separation
matters more than the number.

**What it is.** The same unmodified third-party classes, with test bodies that call the recording
API the way a user following `AsyncTestContext` would. It attaches no agent, on purpose: with both
feeds live a finding could have come from either, and every assertion here depends on each finding
being attributable to a `record*` call the body made. It writes its own report,
`corpus-eval-recording.md`, and its numbers are never merged into the two above.

**Why its ground truth is different.** In the unmodified lanes the class's own javadoc decides
whether a finding is noise, because the body does nothing but share the instance. Here the body
cooperates, and the contract is no longer the question:
`recorded_concurrentReferenceHashMap_checkThenAct` uses a class Spring documents as thread-safe
and uses it with a get-then-put, which is a genuine lost update. Counting that as "a safe subject
with a finding" would read as a false positive when it is the opposite. So each row states what
the body records and what must therefore happen, and the class contract rides along as context.

**Why its assertions are stronger.** `CorpusEvalTest` gates only at the group level, because
whether one particular race is observed in one particular run is probabilistic. A recording-fed
detector's verdict is not: it is a function of the calls the body made. So each subject declares
`MUST_FIRE` or `MUST_STAY_SILENT` and `CorpusGates` holds it to that, per subject, in both
directions. Every detector gets a pair, because one direction alone proves nothing — a detector
that fires on everything passes the MUST_FIRE half, and one that was never wired up passes the
other.

**What those pairs now buy.** A pair of exactly this shape is what the library requires before a
detector may carry `TrustTier.VERDICT`, the one tier safe to fail a merge on. Until 2026-08-29 the
requirement could only be met by tests inside `async-test-lib` itself, because the gate that
enforces it resolves evidence by reflection and this module is downstream of that one: eleven of
the twelve detectors measured here in both directions sat at `PROMPT` for want of a channel, not
for want of evidence.
[`META-INF/async-test/verdict-evidence-corpus`](../../async-test-lib/src/main/resources/META-INF/async-test/verdict-evidence-corpus)
is that channel. It names a detector and its two subjects per line, and both modules check it: the
library refuses a `VERDICT` row that no line and no in-repo pair backs, and `CorpusGates` resolves
every id against the rows here and fails if one is missing, cites the wrong detector or has drifted
to the wrong expectation.

Nine detectors were promoted on that basis, taking `VERDICT` from 10 of 146 to 19:
`SHARED_JSON_MAPPER_RECONFIG`, `SHARED_MESSAGE_DIGEST`, `SHARED_STATEFUL_CRYPTO`,
`CONCURRENT_MAP_COMPUTE_RECURSION`, `SYNCHRONIZED_COLLECTION_ITERATION`, `SHARED_ITERATOR`,
`MUTABLE_MAP_KEY`, `JDBC_CONNECTION_SHARED` and `CONCURRENT_MODIFICATIONS`. Each of those pairs
varies the defect and nothing else on the same class, which is the bar the in-repo twins meet.

### Two that stay PROMPT, and why the pair was not the problem

Three pairs were held back at first because each joined two different classes, so what separated
fire from silence was the class as much as the defect. Revisiting them for
[#406](https://github.com/PIsberg/async-test-lib/issues/406) settled all three, and only one of the
answers was the one the question expected.

**`CONCURRENT_MODIFICATIONS` was the tractable one.** The detector is lock-aware: it intersects the
locks held across every recorded mutation and reports only when that intersection is empty. So a
same-class pair does exist - one `CursorableLinkedList` mutated by six threads with no visible
lock, and a second mutated by six threads under the collection's own monitor. The row was written,
the pair now separates on the synchronization alone, and the detector is promoted.

**`CACHE_CONCURRENCY` cannot have a same-class pair at all.** Its rule asks
`synchronizesItself(cache)`, which tests the map against `ConcurrentMap`, `Hashtable` and the
`Collections$Synchronized` wrappers. That is a question about the type, so given one class both
halves of a pair get the same answer before the run starts. It also consults no lock: a `HashMap`
correctly guarded by the caller's own lock draws the same finding as a raced one, which is the
definition of `PROMPT` rather than a gap to close.

**`CONCURRENT_MAP_CHECK_THEN_ACT` is classified by its caller.** `recordCheckThenAct` is itself the
assertion that a check-then-act happened; the detector's only decision is whether more than one
thread reached the same `(map, key)` site. Its silent row was worse than cross-class - it called no
detector API at all, so a detector that fired on every single record call would have passed it - and
a same-class row was written to fix that: the same map class, the same recorded check-then-act, on a
key private to each thread. The silence is now a decision rather than an absence of calls. It still
does not reach `VERDICT`, because the body declared the defect before the detector saw anything.

| Detector | Must fire | ...did | Must stay silent | ...did |
|---|---:|---:|---:|---:|
| `CacheConcurrencyDetector` | 1 | 1 | 1 | 1 |
| `JdbcConnectionSharedDetector` | 1 | 1 | 1 | 1 |
| `NonAtomicConcurrentMapUpdateDetector` | 1 | 1 | 1 | 1 |
| `ResourceLeakDetector` | 1 | 1 | 1 | 1 |
| `SharedJsonMapperReconfigDetector` | 1 | 1 | 1 | 1 |
| `SharedMessageDigestDetector` | 1 | 1 | 1 | 1 |
| `SharedStatefulCryptoDetector` | 1 | 1 | 1 | 1 |
| `ConcurrentMapComputeRecursionDetector` | 2 | 2 | 2 | 2 |

**It found something on its first run.** `CacheConcurrencyDetector` decided thread safety with
`instanceof ConcurrentHashMap` — one implementation rather than the contract — so Caffeine's
`asMap()` view, whose javadoc says in as many words that it is a thread-safe map, was reported as
a "non-thread-safe cache", and so were Guava's cache, a `ConcurrentSkipListMap` and any user's own
`ConcurrentMap`. The agent path had already learned to ask the interface; the recording path had
not, and nothing compared the two. It now asks `ConcurrentMap`, plus the legacy synchronized
collections that keep the same promise by taking their own monitor. The twin still fires: a plain
`HashMap` read and written from a cache position is what the detector is for.

**And it settled a question the corpus had been deferring.** `JdbcConnectionSharedDetector`
reports a `Connection` reached from more than one thread, and a connection pool hands the same
physical connection to different threads over its lifetime by design - so the pool, which is the
fix the detector's own message recommends, drew a HIGH finding for working exactly as intended.
That is why HikariCP was left out of the fourth wave of subjects: there was no way to tell the two
apart, and a subject producing a finding that is neither a true positive nor noise belongs in
neither column.

The distinction the detector was missing is the one JDBC actually states: at most one thread *at a
time*, not one thread ever. It now takes a release marker, `recordRelease(resource, thread)`, and
when ownership is modelled it reports only threads that held the resource simultaneously. A caller
that never records a release keeps the old behaviour exactly, because a test that never said when
a thread let go has told the detector nothing about overlap, and the safe reading of that silence
is the stricter one.

The pair is sized so the silent half cannot pass for the wrong reason. The pool holds exactly one
connection, so every thread in the run gets the same physical handle - and the lane asserts that
premise rather than assuming it: one connection identity, more than one thread. Without that
check, a pool that quietly opened six connections would produce the same green result while
measuring nothing.

**The second wave: three more pairs, and what each is for**
([#338](https://github.com/PIsberg/async-test-lib/issues/338)). The first four rows came from one
shape: a documented-unsafe receiver against a documented-safe one, recorded identically. The three
added next vary the axis instead, so the lane says something about the fixes as well as the
defects.

`SharedMessageDigestDetector` gets a pair that differs by a **lock**: one SHA-256 instance, the
same six threads on both rows, and one of them holds the digest's own monitor across every access.
`SelfGuard` sees that through `Thread.holdsLock` with no agent attached, so the candidate lock set
never empties and the silence is the guard-on-self probe doing its job. `SharedStatefulCryptoDetector`
gets one that differs by **confinement**: an `HmacSHA256` shared by six threads against a `Mac` per
thread, built identically and recorded the same number of times. That is the direction that catches
a detector keyed on the class rather than on the instance, which would report six correct threads
as a race. `ResourceLeakDetector` gets one that differs by the **release call itself**: a Netty
`ByteBuf` acquired and released inside one body execution, against the identical lifecycle with the
release left out. Both subjects are the JDK's own or Netty's, unmodified, and no new dependency was
needed for any of them.

Each silent row was driven red before being trusted: removing the `synchronized`, replacing the
per-thread `Mac` with the shared one, and dropping the release each flipped exactly its own row to
FIRED and left the others alone. A MUST_STAY_SILENT row that stays silent when the thing it is
about is removed is measuring nothing.

**The eighth row, and the measurement that made it possible**
([#341](https://github.com/PIsberg/async-test-lib/issues/341)).
`ConcurrentMapComputeRecursionDetector` was the fourth candidate of the wave above and was left
out, because the obvious body does not work: a nested `computeIfAbsent` on an absent key never
reaches the inner mapping function, so the second `recordComputeStart` the detector needs could
only be written by hand at the call site. A constructed row, not an observed one.

Probing the rest of the matrix found the shape that does work. Same-key re-entry, JDK 26:

| Implementation | Method | Inner function ran | Outcome |
|---|---|---|---|
| `ConcurrentHashMap` | `computeIfAbsent`, `compute` (absent key) | no | `IllegalStateException: Recursive update` |
| `ConcurrentHashMap` | `merge` (present key) | **yes** | returned normally |
| Caffeine `asMap()` | `computeIfAbsent`, `compute` (absent key) | no | `IllegalStateException: Recursive update` |
| Caffeine `asMap()` | `merge` (present key) | **yes** | returned normally |
| `ConcurrentSkipListMap` | all three | **yes** | returned normally |
| `ConcurrentReferenceHashMap` | all three | **yes** | returned normally |
| Guava `Cache.asMap()` | all three | no | deadlocked, never returned |

The split is a bin-level fact. On an absent key the bin holds a `ReservationNode` and the re-entry
is refused; on a present key it holds a real node whose monitor the re-entry re-acquires, and a
monitor is reentrant, so the nested call completes and the outer return value then overwrites what
it stored. That is a lost update with nothing thrown and nothing logged.

Which reframes what the detector is for. It can only ever observe the middle rows, because its
evidence is a `recordComputeStart` raised from inside a nested mapping function that ran. The
exception rows arrive as a stack trace naming their own cause and the Guava row arrives as a hung
build; both report themselves. The silent one does not, and it is exactly what is left. The
detector's own text used to claim the opposite outcome, an infinite loop or a self-deadlock on the
bin lock, and neither happens on a supported JDK; it now states the measured split.

The pair uses Caffeine's `asMap().merge` on a seeded key, and at this lane's own six threads and
forty invocations, 240 of 240 nested mapping functions ran with nothing thrown.

**And then the rule turned out to be too narrow**
([#343](https://github.com/PIsberg/async-test-lib/issues/343)). The detector keyed its evidence on
map, key and thread together, so it reported a re-entry only when the nested call used the *same*
key. `ConcurrentHashMap`'s contract is not key-scoped: "the mapping function must not modify this
map". The cost was visible in the repository itself. `examples/40-concurrent-map-recursion` is this
detector's own example, its bug is a different-key re-entry (`getNeighbors("A")` calls
`getNeighbors("B")`), and its detection test is `@Disabled`, so nothing had ever noticed that
removing `@Disabled` would not produce the finding the README promised.

Measured over 200 fresh maps, single thread, nested `computeIfAbsent` on a different key: it ran
and returned **198 times** and threw twice, on the runs where both keys landed in the same bin. So
the wider shape is not only real, it is the quieter of the two, and quieter is what this detector
is for.

The rule now asks whether the thread was already inside a compute on *this map*, whichever key.
Two rows guard the boundary rather than one: a nested merge on a different key of the same map
must fire, and the identical nesting one map apart must stay silent. That second row is what makes
the change safe to have on by default, because a mapping function that fills some other cache is
ordinary layered-cache code and a rule keyed on the thread alone would report every one of them.
Both were measured at the lane's own shape first: 240 of 240 nested functions ran, nothing thrown.

The example was fixed in the same change, and it is worth saying how, because the failure was not
in the detector alone. Its test recorded around `getNeighbors("A")` rather than inside the mapping
function, which is one balanced start and end per body execution and no nesting at all: it would
have reported nothing under either rule. `GraphService` now exposes two no-op `Consumer<String>`
hooks, the test wires them to the detector, and with `@Disabled` removed the run fails with the
report naming both keys.

That is the argument for the lane in one line. Twelve detectors of 146 is not coverage; it is the
first twelve rows of a table that had none, and they have already been enough to find a defect that
had been shipping, to settle a modelling question that had been open since the fourth wave, to
correct a detector that was describing a failure mode the platform stopped having, to catch an
example demonstrating a bug its own detector could not see, and - with the ninth row - to find
three registrations that erased their own evidence.

**The ninth row: `SynchronizedCollectionIterationDetector`**

`org.apache.commons.collections4.collection.SynchronizedCollection` states the contract in its
class javadoc rather than leaving it to folklore, which is what makes it a corpus subject:

> Iterators must be manually synchronized:
> `synchronized (coll) { Iterator it = coll.iterator(); ... }`
> - `org/apache/commons/collections4/collection/SynchronizedCollection.java:29`

Both rows traverse an identical decorator and differ in one bit, the `holdingLock` flag, so the
detector is handed the same evidence apart from the thing its model turns on. The class is
documented thread-safe and the unlocked caller is still wrong, which puts this with the
check-then-act pair rather than with the unsafe-type pairs.

Writing it found the defect above. `recordIterationStarted` returns early for a wrapper it does
not know, so registration is load-bearing - and `recordWrapperCreated` installed a fresh
`WrapperInfo` on every call, discarding the iterations counted so far. An `@AsyncTest` body runs
once per worker, and the detector's own usage example calls it from inside one. Two sibling
methods, `recordFutureCreated` and `recordExecutorCreated`, had the same shape. The row could not
have been written without hitting it, which is the argument for the lane restated: a denominator
is a place where a detector has to actually work.

**The tenth row: `SharedIteratorDetector`**

Guava documents `ConcurrentHashMultiset` as *"a multiset that supports concurrent modifications
and that provides atomic versions of most `Multiset` operations"* -
`com/google/common/collect/ConcurrentHashMultiset.java:50`. The detector's own message claims the
hazard stands *"even when that collection is itself a concurrent collection"*, and this is the row
that holds it to that claim: a concurrent collection buys its iterator nothing, because the cursor
is unsynchronized state of its own.

Both rows call `hasNext()` on an iterator of the same collection and differ in one thing, whether
the iterator object is shared. `hasNext()` rather than `next()` because it does not consume: a
shared iterator drained by 240 body executions would end the run on `NoSuchElementException`
instead of measuring anything. The shared row fired; the per-thread row, where every instance is
touched only by the thread that created it, stayed silent.

This one is the counterpart to the check-then-act pair in a different way. There the class was
thread-safe and the *sequence* was wrong; here the class is thread-safe and the *sharing* is.

**The twelfth row: `MutableMapKeyDetector`, the tightest pair in the lane**

Both rows are the same commons-lang3 `MutableInt`, filed as a key in the same map the same way.
The only difference is whether the body then mutates it. Nothing about the subject separates them
- not the type, not the contract, not the call sequence up to that point - so the detector's model
is the only thing that can, which is what a both-directions pair is supposed to test.

It is also the lane's first row whose hazard is not a race at all. A key mutated after insertion
moves its hash away from the bucket the map filed it under, and no amount of synchronization
repairs that. The corpus is mostly about what concurrency does to correct code; this row is about
a contract the caller broke, which concurrency then makes harder to see.

**The eleventh row: `ConcurrentModificationDetector`, and what it could not be**

commons-collections4's `CursorableLinkedList` says in bold that the implementation is not
synchronized, so the MUST_FIRE row is straightforward: six threads mutating it, and the detector
gets it right.

The silent twin is a JDK `CopyOnWriteArrayList`, and that is worth explaining rather than passing
over. It is not a convenience. The detector recognises safety by package prefix, so guava's
`ConcurrentHashMultiset` and commons-collections4's `SynchronizedCollection` both report despite
documenting thread safety - the false positive filed as #395. Until that is settled **no
third-party collection can hold this row at all**, because every one of them fires. The pair
measures the model that exists, and the reason it looks JDK-shaped is itself the finding.

**The third wave: three JDK pairs, and the lockset the map detector lacked (2026-08-31)**

The ceiling section below classifies detectors by what their record path will accept, and its
premise - a recording row needs a *third-party* subject - had already been quietly outgrown by
the second wave: the `MessageDigest` and `Mac` pairs use the platform itself as the subject
library, with the version read from `Runtime.version()` and contracts cited from the JDK's own
javadoc. The third wave leans on that deliberately. Three detectors the third-party route could
not reach now have pairs, taking the lane from twelve detectors to fifteen:

- **`SHARED_BYTE_BUFFER`.** The netty `ByteBuf` candidate was rejected below for having no
  documented contract; `java.nio.Buffer` states one - *"Buffers are not safe for use by multiple
  concurrent threads"* - and the mutable state behind that sentence is the cursor that only
  relative operations touch. Both rows share one `ByteBuffer` across six threads and differ only
  in which half of the API the body records: relative `rewind()`/`get()` fires, absolute
  `get(int)` stays silent. The pair separates on the detector's operation-kind model with the
  sharing held constant.
- **`FILE_CHANNEL_POSITION_RACE`.** The inverse contract shape: `FileChannel` is documented
  thread-safe, and the hazard is the one stateful thing the guarantee does not cover, the
  implicit position. Both rows read the same temp file through a shared channel; the
  cursor-advancing `read(ByteBuffer)` fires and the self-contained `read(ByteBuffer, long)` -
  the overload the detector's own message recommends - stays silent. This joins the
  check-then-act and iterator pairs in the thread-safe-class, wrong-caller family.
- **`WEAK_HASH_MAP_SHARED`.** The `instanceof`-gated detector the ceiling names as its example
  takes the JDK map itself as the subject, and writing the pair found a defect that had been
  shipping. The guarded twin - every access inside `synchronized (map)`, the external
  synchronization `WeakHashMap`'s own javadoc asks for - fired as loudly as the unguarded row:
  the detector had never joined the `SelfGuard.TrackedInstance` lockset rollout, so it could not
  see any guard at all. It carries the lockset now, `SharedTypeAccuracyEvalTest` pins both
  directions (the family table in
  [detector-accuracy-eval.md](detector-accuracy-eval.md) moved from 17 of 19 to 18 of 20), and
  the corpus row is what holds the fix.

The `WeakHashMapShared` finding is the lane's argument repeated a third time: the defect was not
reachable by any unit test that already existed, because every existing test recorded unguarded
sharing and asserted the firing direction. The row that had to stay silent is what forced the
model to distinguish the fix from the bug.

**The fourth wave: two pairs, two refusals (2026-08-31)**

`SHARED_CHARSET_CODER` and `EXECUTOR_SHUTDOWN` take the lane to seventeen detectors, both on the
JDK route. The coder pair is the Mac pair's confinement shape on `CharsetEncoder`, whose class
javadoc states the contract outright ("Instances of this class are not safe for use by multiple
concurrent threads"); the executor pair is a protocol pair like the ResourceLeak rows, on the
lifecycle `ExecutorService`'s javadoc prescribes ("An unused ExecutorService should be shut down
to allow reclamation of its resources") - one declared-owned pool that never records a shutdown
must fire, a fresh pool per body run through the full shutdown-and-await protocol must stay
silent.

The refusals matter as much as the rows. `SHARED_CHECKSUM` and `SHARED_DEFLATER` were the
obvious next two - JDK subjects exist, both detectors carry the lockset, both pairs would have
passed - and neither `CRC32`, `Checksum` nor `Deflater` states one word about thread safety in
its javadoc (checked against the JDK 26 sources). A row would have carried a
`Contract.NOT_THREAD_SAFE` label the platform's own documentation does not support, which is the
folklore this document already flags on the netty rows, and both detectors' fire and silent
directions are pinned per-detector by `SharedTypeAccuracyEvalTest` anyway. A corpus whose
premise is documented contracts adds nothing by restating that eval under an undocumented label,
so those two stay refused until the JDK documents the contract.

**The fifth wave: two lifecycle pairs, three refusals (2026-08-31)**

`TIMER` and `FUTURE_IGNORED` take the lane to nineteen. Both are protocol models on classes the
JDK documents as thread-safe, so both pairs live in the thread-safe-class, wrong-caller family:
a real `TimerTask` that records its uncaught exception and then throws it - really terminating
the timer's single task-execution thread - must fire, and the same schedule-and-complete
lifecycle with nothing thrown must stay silent; a submitted `Future` nobody ever inspects must
fire, and the same submission followed by a recorded inspection and a real `get()` must stay
silent. The timer's silent row deliberately records schedule and complete but not
`recordTaskRun`: the run-to-complete path is judged against a 100 ms wall-clock threshold, and a
MUST_STAY_SILENT row must not be breakable by a GC pause - the same species of choice as the
iterator pair's `hasNext()`.

Three refusals, each for a different reason, all worth keeping:

- **`SHARED_XML_PARSER`.** The famous "an implementation is NOT guaranteed to be thread safe"
  line is nowhere in the JDK 26 sources: neither `DocumentBuilderFactory` nor `DocumentBuilder`
  states anything about thread safety any more. Same rule as the checksum and deflater refusals
  above - no statement, no row.
- **`SHARED_TIMEZONE`.** `TimeZone` has never stated a thread-safety contract. Same rule.
- **`SHARED_KDF`.** The opposite problem: `javax.crypto.KDF` has a model contract - a dedicated
  "Concurrent Access" section saying the methods "are not thread-safe" and callers "should
  synchronize amongst themselves" - and the class exists only since JDK 24, while this module
  keeps a JDK 21 leg because the library targets 21. A row that cannot execute on every leg
  cannot state a MUST outcome the gates hold on every leg, so this pair waits for the corpus to
  drop JDK 21, not for the JDK to document anything.

**The sixth wave: three pairs, and a premise the JVM answers (2026-08-31)**

`NOTIFY_WITHOUT_MONITOR`, `INTERRUPT_SWALLOWING` and `STREAM_CLOSING` take the lane to
twenty-two of the 146.

`NOTIFY_WITHOUT_MONITOR` is the tightest pair the lane holds. Both rows record the identical
call on the identical monitor object, and the only difference is whether the body sits inside
`synchronized (monitor)` - the detector samples `Thread.holdsLock` as the attempt is recorded,
so its own probe is the discriminator and nothing else varies at all. `java.lang.Object` states
the contract in the `@throws` clause of `notify`/`notifyAll`: `IllegalMonitorStateException` "if
the current thread is not the owner of this object's monitor".

That row also carries the lane's second measured premise, after the pooled connection's. Its 240
findings all rest on the claim that the recording thread genuinely does not hold the monitor,
and the JVM is the only authority on that - so the row really calls `notifyAll()` outside the
monitor once, and `theIllegalNotifyReallyThrew()` fails the run unless the JVM threw. The gate
was verified by breaking it: wrapping that one call in `synchronized` makes it report "The JVM
said: returned normally" and go red, which is the difference between a gate and a comment.

`INTERRUPT_SWALLOWING` is a caller-declares model and the rows say so, which is also why the
detector sits at `PROMPT`: the finding is only as good as the declaration behind it. Both bodies
suffer a real `InterruptedException` - self-interrupt, then `sleep`, so the throw is
deterministic rather than timed - and differ in the one boolean. The silent row restores the
flag as the fix prescribes and then clears it before returning, because the fix under test is
not something to hand to the runner's barrier.

`STREAM_CLOSING` is the ResourceLeak shape on file descriptors: one real file-backed stream
recorded open and never closed must fire, a fresh stream per body opened and closed by the same
thread must stay silent. The loud row leaks exactly one descriptor rather than 240, because the
leak is the point and 240 of them would exhaust the runner instead of demonstrating anything.

**The seventh wave: eight pairs, written from a triage rather than one at a time (2026-08-31)**

The first six waves each discovered their detectors' models by reading them one by one. This one
started from a classification of every remaining recording-fed detector against the four rules a
pair has to satisfy - the silent side must actually call the detector, both outcomes must follow
from the calls rather than from timing, the parameter types must admit a subject we can build,
and the finding must not be a contention note whose correct twin is supposed to fire anyway.
That triage put 84 of the remaining detectors in reach and refused 13, and it is what the
remaining waves are being written from. The refusals are in the two lists below.

The eight rows here fall into three families, which is the point of writing them together -
each family is one model seen through different types:

- **A blocking call is fine alone and a hazard while something is held.** `NESTED_MONITOR_LOCKOUT`,
  `FORK_JOIN_TASK_BLOCKING` and `COMPLETABLE_FUTURE_BLOCKING_CALLBACK` record the identical three
  calls and differ only in whether the block sits inside the monitor, the task, or the callback.
  Moving one line past the release is the entire difference between fire and silence.
- **What you lock on, rather than what you do inside.** `SYNCHRONIZED_ON_LITERAL` and
  `BOXED_PRIMITIVE_LOCK` each swap a JVM-wide shared instance - an interned literal, a cached
  `Integer.valueOf` - for a private final `Object`, with the same recorded acquisition either way.
  Both hazards are invisible at the call site, which is what makes them worth a detector.
- **Each operation is atomic and the sequence is not.** `ATOMIC_NON_ATOMIC_UPDATE` is the
  `AtomicInteger` form of the check-then-act pair, get-then-set against get-then-compareAndSet.

`SPURIOUS_WAKEUP_HAZARD` and `MDC_CONTEXT_LEAK` complete the wave: a wait declared outside its
condition loop against one inside it, and a task ending with a diagnostic key it did not start
with against one ending exactly as it began.

**A measurement bug this wave caught, in the eval rather than in a detector.** Wave 7's first run
failed on wave 3's guarded `WeakHashMap` row, which had been green for four waves. The cause was
not the new rows: `~/.m2` held a library jar that predated the `WeakHashMapSharedDetector` lock
fix, so the lane had been resolving a build whose `State` class did not extend
`SelfGuard.TrackedInstance` while the working tree's did. Same version number, different bytes -
the failure mode this repository has already hit with a locally installed vibetags processor. It
is worth stating plainly because the lane's whole output is a measurement: nothing in the report
identifies *which build* of the library produced it, so a stale install changes every number
silently and the only symptom is a row that used to pass. Filed as an issue rather than fixed
here.

**The eighth wave: nine pairs, and two silent rows that fired for different reasons**

`WAIT_TIMEOUT`, `MISSED_SIGNAL`, `OPTIMISTIC_READ_VALIDATION`, `LOCK_UPGRADE_DEADLOCK`,
`SCOPED_VALUE`, `STATEFUL_LAMBDA`, `SYSTEM_PROPERTY_MUTATION`, `WEAK_REFERENCE_RACE` and
`VOLATILE_ARRAY` take the lane to thirty-nine. Seven behaved on the first run. The two that did
not are the interesting part, because they failed the same way - a MUST_STAY_SILENT row fired -
for opposite reasons.

**The lambda row was wrong, and the detector was right.** Its silent twin was
`ThreadLocal.withInitial(() -> () -> { })`, which reads as a lambda per thread and is not one: a
non-capturing lambda is a JVM-wide singleton, so every worker received the same object and
`StatefulLambdaDetector`, which keys on identity, correctly reported it as shared. Measured on
JDK 26 before changing anything: the non-capturing form yields **1** distinct instance across
four threads, the capturing form yields **4**. The row now captures per-thread state, and the
comment records the measurement so the next person writing a confinement twin does not repeat it.

**The array row was right, and the detector was wrong.** `VolatileArrayDetector.findArrayInfo`
resolved an access by `info.array == array || info.name.equals(arrayName)`, so distinct arrays
sharing a label collapsed onto whichever registered first: the second worker's registration
found the first worker's entry and returned early, and every worker's writes then landed in that
one entry. Six private arrays read as one array written by six threads. That is a false positive
on the standard per-thread-buffer pattern - a `ThreadLocal<int[]>` registered under one stable
name in every worker is confined, correct code - and it is the same shape as the `WeakHashMap`
defect in wave 3: the detector reported the fix as loudly as the bug. Registration now asks for
identity alone, and access resolution prefers identity and keeps the label only as a fallback for
an array that was never registered. `VolatileArrayDetectorTest` pins both directions, and the
failing direction was confirmed by reverting the one line and watching it go red.

Two waves, two detector defects, both found by a row that had to stay silent rather than by a row
that had to fire. That asymmetry is the argument for pairs restated: the firing direction is what
a detector's own unit tests already cover, and the silent direction is where the false positives
live.

**The ninth wave: ten pairs, and the first wave where nothing broke**

`COMPLETABLE_FUTURE_EXCEPTIONS`, `COMPLETABLE_FUTURE_COMPLETION_LEAKS`, `UNBOUNDED_QUEUE`,
`COPY_ON_WRITE_COLLECTIONS`, `PARALLEL_STREAMS`, `THREAD_LOCAL_LEAKS`, `DOUBLE_CHECKED_LOCKING`,
`SYNCHRONIZED_NON_FINAL`, `FINAL_FIELD_MUTATION` and `PUBLIC_LOCK_EXPOSURE` take the lane to
**49 of 146**, in 101 rows. All twenty behaved on the first run, which is worth stating only
because the previous two waves did not: the triage's job was to predict which models would
separate structurally, and on this batch it was right ten times out of ten.

Three of them are worth a sentence each for the shape they add:

- **`COPY_ON_WRITE_COLLECTIONS` is the first pair where both halves are the same class under
  different workloads.** The subject is correct by construction - a `CopyOnWriteArrayList` is
  thread-safe whatever you do to it - so the only thing that can separate fire from silence is
  the read/write mix, write-heavy against read-heavy. It is the model for every advisory
  detector whose finding is "right answer, wrong data structure".
- **`DOUBLE_CHECKED_LOCKING` separates on a single boolean** among four declared flags: both
  checks, inside synchronized, and the field volatile or not. That is the whole difference
  between the broken singleton and the version that has been correct since Java 5.
- **`FINAL_FIELD_MUTATION`'s silent row records reads and nothing else.** Every recorded mutation
  fires unconditionally, so the correct twin is a field that is only read - which still hands the
  detector traffic and still makes it decide, rather than being the empty row #410 removed.

**The tenth wave: the synchronizers, and keys that cannot be borrowed**

`CYCLIC_BARRIER`, `REENTRANT_LOCK`, `PHASER`, `EXCHANGER` and `CONDITION_VARIABLES` take the
`java.util.concurrent` coordinators in one batch, because their detectors all ask one question:
did the protocol complete, or did it end in the state the class documents as terminal? Each pair
records a finished cycle against an abandoned one - a barrier left broken against one that
arrives, awaits and completes; a `tryLock` that timed out against a lock taken and released; a
phaser terminated against one that advanced a phase; an exchange carrying null against one
carrying a payload; an await nobody signalled against the whole handshake.

`ABA_PROBLEM`, `STABLE_VALUE_MISUSE` and `VAR_HANDLE_NON_ATOMIC_UPDATE` complete the wave at
**57 of 146**, in 117 rows.

Those last three needed something the earlier waves did not. Their detectors accumulate over the
whole run, keyed on a caller-supplied name, so a silent row using a fixed name would let one body
execution's calls satisfy another's - a `recordSet` from invocation 7 answering for the
`recordRead` in invocation 8, and the row passing for a reason that has nothing to do with the
model. Per-thread keys are not enough either, because each thread runs the body forty times. The
lane now has a `perInvocation(...)` helper that appends a monotonic counter, and the three rows
use it. This is the run-wide accumulation hazard the triage flagged, met for the first time.

`VAR_HANDLE_NON_ATOMIC_UPDATE` is worth one more line: it is the `AtomicInteger` pair one level
down. A plain get and a plain set are neither atomic nor ordered, and the twin expresses the same
read-modify-write as a volatile read plus an atomic update - so the pair separates purely on the
access mode the caller asked for, which is the whole of what a `VarHandle` lets you choose.

**The eleventh wave: threads as the subject, and a three-band classifier**

`THREAD_LEAKS`, `UNCAUGHT_EXCEPTION_HANDLER`, `DAEMON_THREAD_HYGIENE`, `THREAD_FACTORY`,
`INHERITABLE_THREAD_LOCAL`, `THREAD_LOCAL_CONTAMINATION`, `LAMBDA_LOST_UPDATE`,
`RECORD_MUTABLE_COMPONENT_LEAK` and `SHARED_SPLITTABLE_RANDOM` take the lane to **66 of 146**, in
135 rows. These are the first detectors whose subject is a thread rather than something threads
share: was it joined, did anyone hear it die, will it hold the JVM open, was it built with the
name and handler a pool needs.

**Two rows here need a thread that is genuinely alive at analysis**, which no earlier row did.
`ThreadLeakDetector` only reports a tracked thread still `isAlive()`, and the whole point of the
daemon-hygiene row is a non-daemon thread - the one kind that keeps the JVM from exiting. Both
use a thread parked on a latch for the run rather than starting 240 of their own, and the latch
is released as the **first statement** of `reportAndGate`, before any assertion: a gate that
fails must not be able to leave the JVM unable to exit. That ordering is the row's real safety
property and is worth stating, because getting it wrong turns a red test into a hung build.

**The record row was wrong before it was right, for the third time in this series.**
`RECORD_MUTABLE_COMPONENT_LEAK`'s loud row first held a `CopyOnWriteArrayList` and stayed silent.
Reading the detector rather than guessing showed why: it sorts a component into three bands, not
two. Immutable is silent; a `java.util.concurrent` collection is *also* silent, with the source
comment "mutable on purpose, and safely"; and only an unsynchronized mutable component fires.
That exemption is correct - sharing a record that holds a concurrent collection is not the
hazard - so the row moved to a plain `ArrayList`. As with the non-capturing lambda in wave 8, the
pair caught the row rather than the detector, and the reason is now in a comment beside the field
so the next person does not rediscover it.

**The twelfth wave: two families that are really one question each**

Ten pairs take the lane to **76 of 146**, in 155 rows, and they divide cleanly in two.

The five `CompletableFuture` detectors all watch the same class and ask different questions about
what the caller did with the pipeline: was the chain ever terminated
(`COMPLETABLEFUTURE_CHAIN`), is the pool it blocks on the pool that has to run it
(`CF_COMMON_POOL_BLOCKING`), did two threads race to complete it
(`COMPLETABLE_FUTURE_COMPLETION_RACE`), did a cancel reach the work
(`COMPLETABLE_FUTURE_CANCELLATION_PROPAGATION`), was the combinator awaited
(`COMPLETABLE_FUTURE_COMBINATOR_MISUSE`). Every one of them is a thread-safe class with a wrong
caller, which is now the largest family in the lane.

The four structured-concurrency detectors ask one question in four places: did the lifecycle
complete, or did it skip a step? A scope closed without forking, a scope closed without joining,
a joiner bound to two scopes, a result handle read after its scope closed - against the same
lifecycle run to completion.

One row in the *first* family is worth a line for its citation.
`COMPLETABLE_FUTURE_CANCELLATION_PROPAGATION`'s loud half records a cancel with
`mayInterruptIfRunning` set, and `CompletableFuture`'s own javadoc says that parameter has no
effect on it - so a caller who passes `true` and believes the work stopped is relying on
something the class documents as not happening.

All twenty rows behaved on the first run. Six of the ten needed `perInvocation(...)` keys, which
is now routine rather than a discovery.

### How far this lane can go, and where it stops

"Seventy-six of 146" invites the reading that 70 rows are waiting to be written. They are not,
and the ceiling is worth stating so nobody spends a week discovering it one detector at a time.

A recording row needs a third-party subject the detector can actually accept. Classifying every
detector's `record*`/`register*` parameter types, and any `instanceof` gate on the record path:

| | Detectors | What it means for this lane |
|---|---:|---|
| Broad-typed (`Object`, `Map`, `Collection`), no JDK gate | 93 | a third-party subject is possible |
| Typed to a JDK *interface* (`ExecutorService`, `Future`, `Checksum`) | 10 | a third-party *implementation* is possible |
| Typed to a JDK concrete class, or `instanceof`-gated to one | 34 | **no third-party subject exists to write** |
| No `record*` API at all | 12 | agent-fed or zero-config; not this lane's business |

So the third-party reachable set is around 103, not 146. The 34 are not a backlog for a
third-party corpus. `WeakHashMapSharedDetector` ends its record path with
`else return; // not our concern` after testing for `WeakHashMap` and `IdentityHashMap`;
`SimpleDateFormatDetector`'s API is typed to `SimpleDateFormat`. For those, a third-party row
would be silent because of the type system rather than because of the model, and a negative that
the compiler guarantees measures nothing. What the 34 *can* take is the JDK type itself, which is
how the third wave wrote the `WEAK_HASH_MAP_SHARED` pair above; the constraint that survives the
type system is the same one as everywhere else in the corpus, a javadoc that actually states a
contract.

That distinction rejected three candidates while the ninth and tenth rows were being written, and
each rejection is worth more than the row would have been:

- **netty `ByteBuf`** for `SharedByteBufferDetector`. 2508 lines of javadoc and not one mention of
  thread safety, so there is no documented contract to cite. It is also why the existing netty row
  cites `ByteBufAllocator` instead. The third wave's `java.nio.ByteBuffer` pair is the same
  detector reached from the other side: the JDK buffer's javadoc does state the contract.

  Worth recording while it is in hand: the two `recorded_nettyByteBuf_*` rows carry
  `Contract.NOT_THREAD_SAFE`, and that label is the one part of them the javadoc does not support.
  Nothing rests on it - they are `RESOURCE_LEAKS` rows, `RecordingSubject` carries no citation
  field, and their rationale is reference counting, which *is* documented: `ByteBuf implements
  ReferenceCounted`, "a reference-counted object that requires explicit deallocation". So the rows
  are sound and the label is folklore. Left as it is rather than changed, because relabelling a
  subject moves numbers in a published table, but a corpus whose premise is documented contracts
  should not have an undocumented one sitting in it unremarked.
- **commons-lang3 `FastDateFormat`** for `SimpleDateFormatDetector`. The citation is perfect -
  *"a fast and thread-safe version of `SimpleDateFormat`"*, and *"`SimpleDateFormat` is not
  thread-safe in any JDK version"* - and the row cannot be written, because the detector's
  parameter type will not accept it.
- **commons-collections4 `ReferenceMap`** for `WeakHashMapSharedDetector`, for the `instanceof`
  gate above.
- **`NonAtomicConcurrentMapUpdateDetector`**, which looked like an uncovered detector and is
  `DetectorType.CONCURRENT_MAP_CHECK_THEN_ACT` under a different class name. Already covered. The
  class name and the type name differ across the roster often enough that the shortlist has to be
  built from `DetectorTrust` rows rather than from file names.
- **guava `ListenableFuture` and `ListeningExecutorService`** for the future and executor family.
  These were the last third-party-capable group - everything else in the uncovered set takes a JDK
  primitive - and neither documents a thread-safety contract to cite.
- **guava `AtomicLongMap`** for `HighContentionAtomicDetector`. It has a good contract and is
  already a subject, but the detector's model turns on how much contention was observed, not on
  what the class promises. A pair there would be separated by the size of the numbers rather than
  by the documented contract, which is not what this lane measures.

**The thirteen the triage refused.** Six because the correct twin cannot be recorded at all - the
detector's only recording method produces a finding for every event it receives, so a silent row
would be a row that made no call, which is the shape #410 removed from this lane rather than the
shape to add back:

| Detector | Why no correct twin exists |
|---|---|
| `VIRTUAL_THREAD_PINNING` | every recorded pinning event is a finding; the platform-thread variant records nothing |
| `THREAD_POOL_DEADLOCK` | any `nestedSubmissionCount > 0` fires, whatever the pool size |
| `THIS_ESCAPE` | reports every instance with a non-empty escape set; the correct twin's calls are no-ops |
| `THREAD_LOCAL_RANDOM_MISUSE` | `ThreadLocalRandom.current()` is a JVM-wide singleton, so no per-thread instance exists to confine |
| `COMPLETABLE_FUTURE_OBTRUDE_ABUSE` | `recordObtrude` is the only method and every entry is a violation |
| `DEPRECATED_THREAD_API` | `recordApiUse` is the only method and every entry is a violation |

Seven more because the outcome is not a function of the recorded calls. A row whose expectation
a GC pause or a core count can flip is a flaky gate, and this lane's whole claim is that its
expectations are structural:

| Detector | What the outcome actually depends on |
|---|---|
| `FALSE_SHARING` | an experimental flag, a 100-access threshold, and two fields' accessing-thread sets differing |
| `MEMORY_ORDERING` | a write and a read landing adjacent in a concurrently appended log, from different threads |
| `THREAD_STARVATION` | elapsed `nanoTime` against a 1000 ms threshold |
| `LOCK_DOWNGRADE` | the structural branch is deferred to `LockUpgradeDeadlockDetector` by the registry; what is left needs a cross-thread gap |
| `PLATFORM_THREAD_PER_TASK` | a probe task against a 200 ms deadline |
| `VIRTUAL_THREAD_CPU_BOUND` | a measured segment against a 50 ms threshold |
| `VIRTUAL_THREAD_CARRIER_EXHAUSTION` | concurrently blocked threads against `availableProcessors`, so it fires on small runners |

**Where that leaves it.** 49 RECORDING-fed detectors still have no row. That figure is the one
the feed table yields directly - 146 detectors, 18 agent-fed, 3 zero-config, leaving 125
recording-fed, of which 76 are paired here - and it replaces a "47" that earlier revisions of
this paragraph decremented wave by wave without anyone being able to re-derive it. Eighteen of
the 49 are refused with the reason on record: five for want of any documented contract, and the
thirteen the triage rejected as having no recordable correct twin or no structural outcome. The
rest take JDK primitives - locks,
latches, `wait`/`notify`, scopes, threads. A corpus of third-party subjects has nothing to offer
them; the third wave's route, a JDK subject whose own javadoc states a contract, is open to some
of them but is not a backlog either, because most of those primitives' javadocs state a usage
protocol rather than a thread-safety contract. Extending the lane with another library instead
means a new corpus dependency, and `docs/DEPENDENCIES.md` makes that a proposal with a reason
rather than an install.

The binding constraint is not the detector count. It is finding a library class whose own javadoc
states a contract that exercises the detector's model, and the eight corpus libraries only contain
so many. Rows should keep being added while that holds and stop when it stops, rather than being
padded out to a number.


## Reproducing it

```bash
mvn install -DskipTests -Djacoco.skip=true    # the reactor, so the module can resolve the version
mvn -f corpus-eval/pom.xml test               # writes all three lanes under corpus-eval/target/corpus-eval/
```

The generated reports carry the lane, the JVM, the OS, the configuration, the exposure tables and
the per-subject rows for that run. The tables in this document are copies of one such run, not a
second source of truth: when the two disagree, the generated files are right and this document is
stale. Adding `debug=true` to the agent option prints every instrumented type and every class the
JVM refused to re-weave, which is how the round-four numbers above were taken.
