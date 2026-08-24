# Corpus Eval: What the Detectors Say About Code Nobody Here Wrote

_Produced by the standalone [`corpus-eval/`](../../corpus-eval) module, whose gates run on every
execution. Last extended 2026-08-24 for [#302](https://github.com/PIsberg/async-test-lib/issues/302):
four more libraries, eight more subjects, an exposure denominator on every rate, a control lane
with the agent detached, and a platform key on the numbers._

The [detector-accuracy eval](detector-accuracy-eval.md) measures 17 of the 142 detectors against
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
| `RECORDING` | 137 | no, nothing here calls a `record*` API | no, the same |

So the attached lane exposes 5 detectors of 142 and the control lane 3. That is the denominator
for everything below, and it is checked rather than asserted: `CorpusGates` fails the run if a
detector the feed table says cannot be fed reports anyway, and fails the control lane if either
agent-fed detector is heard from at all. The control lane's measured result is zero findings from
zero exposed agent-fed detectors, which is what makes the attached lane's findings attributable to
the agent rather than to the harness.

Per exposed detector, over the 22 documented-safe and 20 documented-unsafe subjects:

| Detector | Feed | Safe exposed | ...with a finding | Unsafe exposed | ...with a finding |
|---|---|---:|---:|---:|---:|
| `AtomicityValidator` | AGENT | 22 | 3 | 20 | 16 |
| `SharedCollectionDetector` | AGENT | 22 | 0 | 20 | 12 |
| `DeadlockDetector` | ZERO_CONFIG | 22 | 0 | 20 | 0 |
| `LivelockDetector` | ZERO_CONFIG | 22 | 0 | 20 | 0 |
| `StaticInitDeadlockDetector` | ZERO_CONFIG | 22 | 0 | 20 | 0 |

The three zero-config rows are the ones worth reading twice. They are exposed on all 42 subjects
and reported nothing, which is a measured zero: none of these subjects deadlocks, livelocks or
parks in a class initializer, and the detectors that would have said so were running. The 137
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

### Divergences to expect, and how to read one

A cell that differs between JDKs is not automatically noise. Two differences are predicted by the
library rather than observed by it, and are declared here so a reader does not have to guess:

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
  column is a symptom, not a measurement, and the gate does not read it per subject. Consecutive
  runs on one platform produce identical finding rows.

Anything else that differs between two cells is worth an issue.

### Per subject, run **L**

| Subject | Library | Contract | Findings | Detectors (tier/severity) | Crashes |
|---|---|---|---:|---|---:|
| `mutableInt_incrementAndGet` | commons-lang3 | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `mutableLong_incrementAndGet` | commons-lang3 | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `stopWatch_splitAndGet` | commons-lang3 | NOT_THREAD_SAFE | 2 | AtomicityValidator, SharedCollectionDetector (PROMPT/HIGH) | 82 |
| `lruMap_putAndGet` | commons-collections4 | NOT_THREAD_SAFE | 2 | AtomicityValidator, SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `flat3Map_putAndGet` | commons-collections4 | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `listOrderedMap_putAndGet` | commons-collections4 | NOT_THREAD_SAFE | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `passiveExpiringMap_putAndGet` | commons-collections4 | NOT_THREAD_SAFE | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `arrayListMultimap_put` | guava | NOT_THREAD_SAFE | 2 | AtomicityValidator, SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `evictingQueue_addAndPoll` | guava | NOT_THREAD_SAFE | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `guavaStopwatch_startStop` | guava | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 11 |
| `statsAccumulator_add` | guava | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `hashMultimap_put` | guava | NOT_THREAD_SAFE | 2 | AtomicityValidator, SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `linkedListMultimap_put` | guava | NOT_THREAD_SAFE | 2 | AtomicityValidator, SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `minMaxPriorityQueue_addAndPoll` | guava | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 49 |
| `hashedMap_putAndGet` | commons-collections4 | NOT_THREAD_SAFE | 2 | AtomicityValidator, SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `linkedMap_putAndGet` | commons-collections4 | NOT_THREAD_SAFE | 2 | AtomicityValidator, SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `multiKeyMap_putAndGet` | commons-collections4 | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `caseInsensitiveMap_putAndGet` | commons-collections4 | NOT_THREAD_SAFE | 2 | AtomicityValidator, SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `lazyMap_get` | commons-collections4 | NOT_THREAD_SAFE | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `objectMapper_reconfigureWhileWriting` | jackson-databind | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `fastDateFormat_format` | commons-lang3 | THREAD_SAFE | 0 | - | 0 |
| `atomicSafeInitializer_get` | commons-lang3 | THREAD_SAFE | 0 | - | 0 |
| `lazyInitializer_get` | commons-lang3 | THREAD_SAFE | 0 | - | 0 |
| `synchronizedBag_addAndCount` | commons-collections4 | THREAD_SAFE | 0 | - | 0 |
| `fixedOrderComparator_compare` | commons-collections4 | THREAD_SAFE | 0 | - | 0 |
| `rateLimiter_tryAcquire` | guava | THREAD_SAFE | 0 | - | 0 |
| `eventBus_post` | guava | THREAD_SAFE | 0 | - | 0 |
| `bloomFilter_putAndMightContain` | guava | THREAD_SAFE | 0 | - | 0 |
| `atomicLongMap_incrementAndGet` | guava | THREAD_SAFE | 0 | - | 0 |
| `concurrentHashMultiset_add` | guava | THREAD_SAFE | 0 | - | 0 |
| `memoizedSupplier_get` | guava | THREAD_SAFE | 0 | - | 0 |
| `joiner_join` | guava | THREAD_SAFE | 0 | - | 0 |
| `splitter_splitToList` | guava | THREAD_SAFE | 0 | - | 0 |
| `patternFilenameFilter_accept` | guava | THREAD_SAFE | 0 | - | 0 |
| `fileBackedOutputStream_writeAndReset` | guava | THREAD_SAFE | 0 | - | 0 |
| `objectMapper_configuredThenShared` | jackson-databind | THREAD_SAFE | 0 | - | 0 |
| `objectReader_readValue` | jackson-databind | THREAD_SAFE | 0 | - | 0 |
| `objectWriter_writeValueAsString` | jackson-databind | THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `caffeineCache_getAndPut` | caffeine | THREAD_SAFE | 0 | - | 0 |
| `caffeineAsMap_computeIfAbsent` | caffeine | THREAD_SAFE | 0 | - | 0 |
| `pooledByteBufAllocator_bufferAndRelease` | netty-buffer | THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `concurrentReferenceHashMap_putAndGet` | spring-core | THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |

| Measure | **L** | **L-off** |
|---|---|---|
| Detectors exposed at all | 5 of 142 | 3 of 142 |
| Documented-thread-safe classes with a VERDICT-tier HIGH or CRITICAL finding | 0 of 22 | 0 of 22 |
| Documented-thread-safe classes with any finding at all | 3 of 22 | 0 of 22 |
| Documented-not-thread-safe classes with at least one finding | 20 of 20 | 0 of 20 |
| Documented-not-thread-safe classes that threw out of their own code | 3 of 20 | 3 of 20 |
| Distinct detectors that produced any finding | 2 of 5 exposed | 0 of 3 exposed |

## What this means for a user

**The strongest claim held.** `VERDICT` is the tier the library reserves for findings backed by a
measured case that fires on a bug and stays silent on its correctly synchronized twin, and it is
the tier `@AsyncTest(failOn = FailOn.HIGH, minTrust = TrustTier.VERDICT)` gates a merge on. Across
22 classes whose javadoc says they are safe for concurrent use, no finding reached it: a build
gated at that tier would not have failed on any of them. Three drew a `PROMPT`-tier finding, which
is the tier that says "verify this", and all three are traced below.

**What attaching the agent buys, in one number.** Five detectors of 142 could see anything in this
corpus, and two of them produced every finding in it. That is not a defect in the other 140: 137
of them are told what happened by the test body, and this corpus tells them nothing on purpose.
A user attaching the agent to an existing suite and changing no test code is buying the
`AGENT` set; a user willing to record is buying the rest. The control lane is what makes that
concrete: with the agent detached the same 42 subjects produced zero findings, so nothing in the
attached lane's column came from the harness.

## The three findings on documented-thread-safe code

Each was traced to what the model could not see. None was tuned away, and each is an open issue
rather than a closed one, because each needs a rule the analyzer does not have yet.

**`ConcurrentReferenceHashMap$Segment.resizeThreshold` (spring-core).** The field is written under
the segment's own lock and read once without it, at
`ConcurrentReferenceHashMap.java:724`, as a hint for whether a restructure is likely; the
authoritative check is re-made under the lock at line 747 before anything acts on it. The model
sees an unlocked read of a field whose writes are locked and calls it a TOCTOU window, which it
literally is. What it cannot see is that the value is discarded and re-read under the lock before
use. The rule that would close it, "an unlocked read followed in the same operation by a read of
the same field under a lock that covers its writes is a hint, not a decision", is
[#311](https://github.com/PIsberg/async-test-lib/issues/311).

**`LongLongHashMap.mask` and `.maxProbe` (netty-buffer).** Netty builds a pool chunk's metadata
while holding the arena's lock and then serves that chunk under the chunk's own `runsAvailLock`,
so the lockset covering the construction-time writes and the lockset covering every later read do
not intersect. This was verified by construction rather than argued: warming the allocator with 64
single-threaded allocations before the concurrent phase removes the finding completely and changes
nothing else in the table, which places the cause in the construction phase. The subject is
deliberately left unwarmed, because an unwarmed pool is what a user's first allocation meets.
[#312](https://github.com/PIsberg/async-test-lib/issues/312).

**`MapSerializer._dynamicValueSerializers` and `StdKeySerializers$Dynamic._dynamicSerializers`
(jackson-databind).** Jackson's serializer lookup is a racy single-check cache: read a non-volatile
reference, and on a miss compute a fresh immutable map and store it. Threads can lose each other's
writes, and that is fine, because recomputation is idempotent and the stored value is immutable.
The model reports a real race on a non-volatile field, which it is; what it cannot see is that
losing the race costs a recomputation and nothing else.
[#313](https://github.com/PIsberg/async-test-lib/issues/313).

Note which Jackson subjects stayed silent: `ObjectMapper` configured once and then shared,
and `ObjectReader`. The mutant-factory contract that Jackson documents most strongly is the one
the model reads correctly.

## What the corpus taught the model, in four rounds

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

## What this does not measure

- **Forty-two classes from seven libraries is not an ecosystem study.** It bounds the
  false-positive rate at the tier that gates builds, over a stated denominator, and it does not
  support a claim about the JVM ecosystem.
- **137 detectors of 142 are not measured here at all.** Their exposure in both lanes is zero, so
  this corpus says nothing about them in either direction. Measuring them needs a lane whose test
  bodies record what they did, which is a different eval with a different denominator
  ([#310](https://github.com/PIsberg/async-test-lib/issues/310)).
- **A noise column of 3 of 22 is a measurement, not a guarantee.** Nineteen documented-safe classes
  produce nothing, and each of them went quiet because a specific mechanism became visible to the
  model. A correct class guarded by a mechanism the weaver cannot see, a lock acquired inside
  unwoven code, a hand-rolled protocol on plain fields, will still draw a `PROMPT`-tier finding,
  and the tier system exists to price exactly that. The three above are that case, named.
- **Detection is probabilistic, and the gate reflects that.** `CorpusGates` fails the run when a
  documented-thread-safe class draws a VERDICT-tier HIGH or CRITICAL finding, when the unsafe group
  as a whole produces nothing at all, when a detector reports that the feed table says cannot be
  fed, and when the control lane hears from an agent-fed detector. It deliberately does not assert
  that a particular subject fires on a particular run, because that would be a flaky gate rather
  than a measurement.
- **The corpus classes are subjects, not endorsements.** They are on the test classpath of a
  standalone module and reach neither the reactor nor any published artifact.

## Reproducing it

```bash
mvn install -DskipTests -Djacoco.skip=true    # the reactor, so the module can resolve the version
mvn -f corpus-eval/pom.xml test               # writes both lanes under corpus-eval/target/corpus-eval/
```

The generated reports carry the lane, the JVM, the OS, the configuration, the exposure tables and
the per-subject rows for that run. The tables in this document are copies of one such run, not a
second source of truth: when the two disagree, the generated files are right and this document is
stale. Adding `debug=true` to the agent option prints every instrumented type and every class the
JVM refused to re-weave, which is how the round-four numbers above were taken.
