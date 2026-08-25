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

The [detector-accuracy eval](detector-accuracy-eval.md) measures 17 of the 146 detectors against
twins written for the test. It answers "does the analyzer's model hold", and it cannot answer the
question an evaluating team asks first: on code neither the library nor the test author wrote, does
a finding mean something is wrong, and how much noise comes with it. This document answers that on
42 classes from seven third-party libraries.

## What was measured

Forty-two classes from `commons-lang3:3.20.0`, `commons-collections4:4.5.0`, `guava:33.4.8-jre`,
`jackson-databind:2.22.2`, `caffeine:3.2.4`, `netty-buffer:4.2.17.Final` and `spring-core:7.0.9`,
each exercised by one shared instance under `@AsyncTest(threads = 6, invocations = 40)` with
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

- **Twenty classes document themselves as not thread-safe.** Sharing one instance across threads is
  the defect a user would have written, so a finding is a true positive.
- **Twenty-two document themselves as safe for concurrent use.** Sharing one instance is the usage
  the class exists for, so a finding is noise.

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
| `AGENT` | 2 | yes, by the woven field and collection streams | no, there are no woven streams |
| `ZERO_CONFIG` | 3 | yes, by `ThreadMXBean`, thread dumps and the runner | yes, the same |
| `RECORDING` | 141 | no, nothing here calls a `record*` API | no, the same |

So the attached lane exposes 5 detectors of 146 and the control lane 3. That is the denominator
for everything below, and it is checked rather than asserted: `CorpusGates` fails the run if a
detector the feed table says cannot be fed reports anyway, and fails the control lane if either
agent-fed detector is heard from at all. The control lane's measured result is zero findings from
zero exposed agent-fed detectors, which is what makes the attached lane's findings attributable to
the agent rather than to the harness.

Per exposed detector, over the 22 documented-safe and 20 documented-unsafe subjects:

| Detector | Feed | Safe exposed | ...with a finding | Unsafe exposed | ...with a finding |
|---|---|---:|---:|---:|---:|
| `AtomicityValidator` | AGENT | 22 | 0 | 20 | 15 |
| `SharedCollectionDetector` | AGENT | 22 | 0 | 20 | 12 |
| `DeadlockDetector` | ZERO_CONFIG | 22 | 0 | 20 | 0 |
| `LivelockDetector` | ZERO_CONFIG | 22 | 0 | 20 | 0 |
| `StaticInitDeadlockDetector` | ZERO_CONFIG | 22 | 0 | 20 | 0 |

The three zero-config rows are the ones worth reading twice. They are exposed on all 42 subjects
and reported nothing, which is a measured zero: none of these subjects deadlocks, livelocks or
parks in a class initializer, and the detectors that would have said so were running. The 141
recording-fed detectors have no row, because a rate over an exposure of zero is not a rate.

## Results

Every number in this section is keyed to the run it came from. The agent, the memory model and the
scheduler all differ across JDK releases, so a table that quotes one number and names no platform
is not reproducible.

| Key | JDK | OS | Agent | Source |
|---|---|---|---|---|
| **L** | 26 (Temurin) | Windows 11 26200 (amd64) | `fields=true,collections=true` | local run, 2026-08-24 |
| **C21** | 21 (Temurin) | ubuntu-latest | `fields=true,collections=true` | e2e-tests workflow, `Corpus Eval (Java 21)` |
| **C25** | 25 (Temurin) | ubuntu-latest | `fields=true,collections=true` | e2e-tests workflow, `Corpus Eval (Java 25)` |
| **C26** | 26 (Temurin) | ubuntu-latest | `fields=true,collections=true` | e2e-tests workflow, `Corpus Eval (Java 26)` |
| **L-off** / **C-off** | as above | as above | not attached | the control lane of the same run |

Each CI job uploads both lanes' reports as `e2e-corpus-eval-report-java-<version>`, so any row here
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
| Documented-thread-safe with a VERDICT-tier HIGH or CRITICAL finding | **0 of 22** | **0 of 22** | **0 of 22** | **0 of 22** |
| Documented-not-thread-safe with at least one finding | **20 of 20** | **20 of 20** | **20 of 20** | **20 of 20** |
| Documented-thread-safe with any finding at all | **0 of 22** | **0 of 22** | **0 of 22** | **0 of 22** |
| `mutableInt_incrementAndGet`, events / findings | 1,455 / 1 | 1,455 / 1 | 1,455 / 1 | 1,445 / 1 |
| `rateLimiter_tryAcquire`, events / findings | 6,421 / 0 | 6,431 / 0 | 6,431 / 0 | 6,421 / 0 |
| Telemetry events dropped | 0 | 0 | 0 | 0 |

Every row is now identical on all four platforms: the same 20 subjects detected, the same zero
noise, and per-subject event counts that agree to within 3%. The rows that used to differ were
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
| `mutableInt_incrementAndGet` | commons-lang3:3.20.0 | NOT_THREAD_SAFE | 1455 | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `mutableLong_incrementAndGet` | commons-lang3:3.20.0 | NOT_THREAD_SAFE | 1455 | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `stopWatch_splitAndGet` | commons-lang3:3.20.0 | NOT_THREAD_SAFE | 6849 | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 76 |
| `lruMap_putAndGet` | commons-collections4:4.5.0 | NOT_THREAD_SAFE | 5373 | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `flat3Map_putAndGet` | commons-collections4:4.5.0 | NOT_THREAD_SAFE | 5052 | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `listOrderedMap_putAndGet` | commons-collections4:4.5.0 | NOT_THREAD_SAFE | 1698 | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `passiveExpiringMap_putAndGet` | commons-collections4:4.5.0 | NOT_THREAD_SAFE | 3135 | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `arrayListMultimap_put` | guava:33.4.8-jre | NOT_THREAD_SAFE | 1997 | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `evictingQueue_addAndPoll` | guava:33.4.8-jre | NOT_THREAD_SAFE | 2175 | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `guavaStopwatch_startStop` | guava:33.4.8-jre | NOT_THREAD_SAFE | 2393 | 1 | AtomicityValidator (PROMPT/HIGH) | 10 |
| `statsAccumulator_add` | guava:33.4.8-jre | NOT_THREAD_SAFE | 5121 | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `hashMultimap_put` | guava:33.4.8-jre | NOT_THREAD_SAFE | 1467 | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `linkedListMultimap_put` | guava:33.4.8-jre | NOT_THREAD_SAFE | 4617 | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `minMaxPriorityQueue_addAndPoll` | guava:33.4.8-jre | NOT_THREAD_SAFE | 15403 | 1 | AtomicityValidator (PROMPT/HIGH) | 62 |
| `hashedMap_putAndGet` | commons-collections4:4.5.0 | NOT_THREAD_SAFE | 3925 | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `linkedMap_putAndGet` | commons-collections4:4.5.0 | NOT_THREAD_SAFE | 4212 | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `multiKeyMap_putAndGet` | commons-collections4:4.5.0 | NOT_THREAD_SAFE | 7919 | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `caseInsensitiveMap_putAndGet` | commons-collections4:4.5.0 | NOT_THREAD_SAFE | 3928 | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `lazyMap_get` | commons-collections4:4.5.0 | NOT_THREAD_SAFE | 1217 | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `fastDateFormat_format` | commons-lang3:3.20.0 | THREAD_SAFE | 4511 | 0 | - | 0 |
| `atomicSafeInitializer_get` | commons-lang3:3.20.0 | THREAD_SAFE | 1184 | 0 | - | 0 |
| `lazyInitializer_get` | commons-lang3:3.20.0 | THREAD_SAFE | 1164 | 0 | - | 0 |
| `synchronizedBag_addAndCount` | commons-collections4:4.5.0 | THREAD_SAFE | 4030 | 0 | - | 0 |
| `rateLimiter_tryAcquire` | guava:33.4.8-jre | THREAD_SAFE | 6421 | 0 | - | 0 |
| `eventBus_post` | guava:33.4.8-jre | THREAD_SAFE | 31390 | 0 | - | 0 |
| `bloomFilter_putAndMightContain` | guava:33.4.8-jre | THREAD_SAFE | 27822 | 0 | - | 0 |
| `atomicLongMap_incrementAndGet` | guava:33.4.8-jre | THREAD_SAFE | 911 | 0 | - | 0 |
| `concurrentHashMultiset_add` | guava:33.4.8-jre | THREAD_SAFE | 1393 | 0 | - | 0 |
| `memoizedSupplier_get` | guava:33.4.8-jre | THREAD_SAFE | 1565 | 0 | - | 0 |
| `joiner_join` | guava:33.4.8-jre | THREAD_SAFE | 4031 | 0 | - | 0 |
| `splitter_splitToList` | guava:33.4.8-jre | THREAD_SAFE | 33325 | 0 | - | 0 |
| `patternFilenameFilter_accept` | guava:33.4.8-jre | THREAD_SAFE | 911 | 0 | - | 0 |
| `fixedOrderComparator_compare` | commons-collections4:4.5.0 | THREAD_SAFE | 1391 | 0 | - | 0 |
| `fileBackedOutputStream_writeAndReset` | guava:33.4.8-jre | THREAD_SAFE | 3541 | 0 | - | 0 |
| `objectMapper_reconfigureWhileWriting` | jackson-databind:2.22.2 | NOT_THREAD_SAFE | 119341 | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `objectMapper_configuredThenShared` | jackson-databind:2.22.2 | THREAD_SAFE | 113189 | 0 | - | 0 |
| `objectReader_readValue` | jackson-databind:2.22.2 | THREAD_SAFE | 76365 | 0 | - | 0 |
| `objectWriter_writeValueAsString` | jackson-databind:2.22.2 | THREAD_SAFE | 82201 | 0 | - | 0 |
| `caffeineCache_getAndPut` | caffeine:3.2.4 | THREAD_SAFE | 8703 | 0 | - | 0 |
| `caffeineAsMap_computeIfAbsent` | caffeine:3.2.4 | THREAD_SAFE | 3553 | 0 | - | 0 |
| `pooledByteBufAllocator_bufferAndRelease` | netty-buffer:4.2.17.Final | THREAD_SAFE | 59667 | 0 | - | 0 |
| `concurrentReferenceHashMap_putAndGet` | spring-core:7.0.9 | THREAD_SAFE | 10406 | 0 | - | 0 |

| Measure | **L** | **L-off** |
|---|---|---|
| Detectors exposed at all | 5 of 146 | 3 of 146 |
| Documented-thread-safe classes with a VERDICT-tier HIGH or CRITICAL finding | 0 of 22 | 0 of 22 |
| Documented-thread-safe classes with any finding at all | **0 of 22** | 0 of 22 |
| Documented-not-thread-safe classes with at least one finding | 20 of 20 | 0 of 20 |
| Documented-not-thread-safe classes that threw out of their own code | 3 of 20 | 3 of 20 |
| Distinct detectors that produced any finding | 2 of 5 exposed | 0 of 3 exposed |

The control column is the same on every platform: with nothing attached, all four runs observed
nothing at all from the agent-fed pair, over 42 subjects. Whatever moves between machines moves
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

**What attaching the agent buys, in one number.** Five detectors of 146 could see anything in this
corpus, and two of them produced every finding in it, on every platform. That is not a defect in the other 144: 141
of them are told what happened by the test body, and this corpus tells them nothing on purpose.
A user attaching the agent to an existing suite and changing no test code is buying the
`AGENT` set; a user willing to record is buying the rest. The control lane is what makes that
concrete: with the agent detached the same 42 subjects produced zero findings, so nothing in the
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

- **Forty-two classes from seven libraries is not an ecosystem study.** It bounds the
  false-positive rate at the tier that gates builds, over a stated denominator, and it does not
  support a claim about the JVM ecosystem.
- **137 detectors of 146 are not measured here at all.** Their exposure in every lane is zero, so
  this corpus says nothing about them in either direction. Four more are measured in the
  recording lane below, which is a different eval over a different denominator and is reported
  separately for that reason.
- **A noise column of zero is a measurement, not a guarantee.** All twenty-two documented-safe
  classes produce nothing, and each of the last three went quiet because a specific idiom became
  a rule the analyzer can check, never because a threshold moved. A correct class guarded by a
  mechanism the weaver cannot see, a lock acquired inside unwoven code, a hand-rolled protocol on
  plain fields, will still draw a `PROMPT`-tier finding, and the tier system exists to price
  exactly that. This corpus no longer contains such a class; the next library someone points the
  agent at may.
- **Detection is probabilistic, and the gate reflects that.** `CorpusGates` fails the run when a
  documented-thread-safe class draws a VERDICT-tier HIGH or CRITICAL finding, when the unsafe group
  as a whole produces nothing at all, when a detector reports that the feed table says cannot be
  fed, and when the control lane hears from an agent-fed detector. It deliberately does not assert
  that a particular subject fires on a particular run, because that would be a flaky gate rather
  than a measurement.
- **The corpus classes are subjects, not endorsements.** They are on the test classpath of a
  standalone module and reach neither the reactor nor any published artifact.

## The recording lane: a denominator for detectors the corpus cannot reach

The bullet above used to end this document's account of the 141: exposure zero, nothing said in
either direction. That is now measured for three of them, in a third lane, and the separation
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

| Detector | Must fire | ...did | Must stay silent | ...did |
|---|---:|---:|---:|---:|
| `CacheConcurrencyDetector` | 1 | 1 | 1 | 1 |
| `JdbcConnectionSharedDetector` | 1 | 1 | 1 | 1 |
| `NonAtomicConcurrentMapUpdateDetector` | 1 | 1 | 1 | 1 |
| `SharedJsonMapperReconfigDetector` | 1 | 1 | 1 | 1 |

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

That is the argument for the lane in one line. Four detectors of 146 is not coverage; it is the
first four rows of a table that had none, and it was enough to find a defect that had been
shipping and to settle a modelling question that had been open since the fourth wave.


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
