# Corpus Eval: What the Detectors Say About Code Nobody Here Wrote

_Branch: `fix/corpus-eval-lockset-model` - date: 2026-08-23 - produced by the standalone
[`corpus-eval/`](../../corpus-eval) module, whose gates run on every execution._

_Updated 2026-08-23 for the second time: the first run of the day closed the collection-weaving
gap and brought detection to 19 of 19; this one closes the lock-model gaps that a new
synchronized-method subject exposed, and the noise column now reads zero. What changed and what
each change cost is stated in "What the corpus taught the model, in two rounds"._

The [detector-accuracy eval](detector-accuracy-eval.md) measures 17 of the 142 detectors against
twins written for the test. It answers "does the analyzer's model hold", and it cannot answer the
question an evaluating team asks first: on code neither the library nor the test author wrote, does
a finding mean something is wrong, and how much noise comes with it. This document answers that on
34 classes from three third-party libraries.

## What was measured

Thirty-four classes from `commons-lang3:3.20.0`, `commons-collections4:4.5.0` and
`guava:33.4.8-jre`, each exercised by one shared instance under `@AsyncTest(threads = 6, invocations = 40)` with
`detectAll = true` and the agent attached as `fields=true,collections=true`. No detector is configured, nothing is
recorded by hand, and no line of the subject library is modified. The only thing the test body does
is call the class from six threads at once.

Ground truth is each class's own javadoc, quoted with its file and line in that library's sources
jar in [`Corpus.java`](../../corpus-eval/src/test/java/com/example/corpus/Corpus.java):

- **Nineteen classes document themselves as not thread-safe.** Sharing one instance across threads is
  the defect a user would have written, so a finding is a true positive.
- **Fifteen document themselves as safe for concurrent use.** Sharing one instance is the usage the
  class exists for, so a finding is noise. The fifteenth row is Guava's `FileBackedOutputStream`,
  added in this round because the corpus had no subject built on `synchronized` methods, the most
  ordinary correct idiom in Java, and the model turned out to be blind to exactly that.

A test method with no corpus row fails the run, so a subject cannot be exercised without a
documented contract behind it.

## Results

Measured on JDK 26 on Windows 11. Consecutive runs produce identical per-subject rows; only the
exception counts move, and only on the three subjects whose corruption surfaces as a throw.

| Subject | Contract | Findings | Detectors (tier/severity) | Crashes |
|---|---|---:|---|---:|
| `mutableInt_incrementAndGet` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `mutableLong_incrementAndGet` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `stopWatch_splitAndGet` | NOT_THREAD_SAFE | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 78 |
| `lruMap_putAndGet` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `flat3Map_putAndGet` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `listOrderedMap_putAndGet` | NOT_THREAD_SAFE | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `passiveExpiringMap_putAndGet` | NOT_THREAD_SAFE | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `arrayListMultimap_put` | NOT_THREAD_SAFE | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `evictingQueue_addAndPoll` | NOT_THREAD_SAFE | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `guavaStopwatch_startStop` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 8 |
| `statsAccumulator_add` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `hashMultimap_put` | NOT_THREAD_SAFE | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `linkedListMultimap_put` | NOT_THREAD_SAFE | 2 | AtomicityValidator (PROMPT/HIGH), SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `minMaxPriorityQueue_addAndPoll` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 62 |
| `hashedMap_putAndGet` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `linkedMap_putAndGet` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `multiKeyMap_putAndGet` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `caseInsensitiveMap_putAndGet` | NOT_THREAD_SAFE | 1 | AtomicityValidator (PROMPT/HIGH) | 0 |
| `lazyMap_get` | NOT_THREAD_SAFE | 1 | SharedCollectionDetector (PROMPT/HIGH) | 0 |
| `fastDateFormat_format` | THREAD_SAFE | 0 | - | 0 |
| `atomicSafeInitializer_get` | THREAD_SAFE | 0 | - | 0 |
| `lazyInitializer_get` | THREAD_SAFE | 0 | - | 0 |
| `synchronizedBag_addAndCount` | THREAD_SAFE | 0 | - | 0 |
| `rateLimiter_tryAcquire` | THREAD_SAFE | 0 | - | 0 |
| `eventBus_post` | THREAD_SAFE | 0 | - | 0 |
| `bloomFilter_putAndMightContain` | THREAD_SAFE | 0 | - | 0 |
| `atomicLongMap_incrementAndGet` | THREAD_SAFE | 0 | - | 0 |
| `concurrentHashMultiset_add` | THREAD_SAFE | 0 | - | 0 |
| `memoizedSupplier_get` | THREAD_SAFE | 0 | - | 0 |
| `joiner_join` | THREAD_SAFE | 0 | - | 0 |
| `splitter_splitToList` | THREAD_SAFE | 0 | - | 0 |
| `patternFilenameFilter_accept` | THREAD_SAFE | 0 | - | 0 |
| `fixedOrderComparator_compare` | THREAD_SAFE | 0 | - | 0 |
| `fileBackedOutputStream_writeAndReset` | THREAD_SAFE | 0 | - | 0 |

| Measure | Value |
|---|---|
| Documented-thread-safe classes with a VERDICT-tier HIGH or CRITICAL finding | 0 of 15 |
| Documented-thread-safe classes with any finding at all | 0 of 15 |
| Documented-not-thread-safe classes with at least one finding | 19 of 19 |
| Documented-not-thread-safe classes that threw out of their own code | 3 of 19 |
| Distinct detectors that produced any finding | 2 of 142 |

## What this means for a user

**The strongest claim held, and now the weaker ones do too.** `VERDICT` is the tier the library
reserves for findings backed by a measured case that fires on a bug and stays silent on its
correctly synchronized twin, and it is the tier `@AsyncTest(failOn = FailOn.HIGH, minTrust =
TrustTier.VERDICT)` gates a merge on. Across 15 classes whose javadoc says they are safe for
concurrent use, no tier produced anything at all: a build gated at any trust level would not have
failed on any of them, and every finding in the table above sits on a class whose own
documentation says sharing it is a bug.

**An earlier version of this document explained the one remaining finding as a boundary of the
technique.** Guava's `EventBus` reaches a cache guarded by striped locks, and the explanation went
that an Eraser lockset cannot bless striping. That explanation was wrong, and the proof was a
twin: a synthetic class with the exact locking shape of Guava's cache went silent while the cache
itself kept firing. The real causes were three mechanical gaps, each since closed and each pinned
by a twin pair: the lockset compared whole sets for equality instead of intersecting, the accessor
`Advice` double-reported every getter and setter under one instance-blind key, and the collection
hooks recorded a stateless no-op queue as corruptible state. Per-partition reasoning was never
needed; seeing the locks that were actually held was.

## What the corpus taught the model, in two rounds

Every rule below was added because a documented contract disagreed with a finding, was traced to
something the model did not know, and generalises beyond the subject that exposed it. Detection
stayed at 19 of 19 through all of them, which is the number that matters while chasing the noise
column: a rule that quietens a false positive by weakening detection has not fixed anything.

**Round one: reach.** Field weaving alone saw 15 of 19 documented-unsafe classes, because a class
that keeps its state in a JDK collection writes no field of its own and the agent cannot weave
`java.util`. The agent's `collections=true` mode rewrote the collection call sites and closed
that gap:

| Measure | Field weaving only | With collection weaving |
|---|---|---|
| Documented-not-thread-safe classes with a finding | 15 of 19 | **19 of 19** |
| Documented-thread-safe classes (14 then) with a VERDICT-tier HIGH or CRITICAL finding | 0 | 0 |

**Round two: precision.** With reach in place, the noise column was measured at 2 of 15, and each
finding was traced to a model gap rather than tuned away:

| Was reported | Because | Now |
|---|---|---|
| `FileBackedOutputStream.out` | a `synchronized` method compiles to `ACC_SYNCHRONIZED` and no monitor instruction, so the woven lockset saw nothing held | every woven access carries its receiver, probed with `Thread.holdsLock`, and the monitor of an enclosing `synchronized` method |
| `LocalCache$WeakEntry.valueReference` | the load path writes under `synchronized (entry)` plus the segment lock while other writes hold the segment lock alone, and lockset equality collapses `{A}` versus `{A, B}` | locksets are registered once per distinct set and intersected; `{A}` covers both |
| the same field, a second stream | with `fields=true` the accessor `Advice` still reported every getter and setter, with no instance identity and no volatile flag | the `Advice` stands down when field instructions are woven; the field stream carries strictly more |
| `LocalCache$2` (the no-op discarding queue) | the collection hooks recorded every receiver reaching a `java.util` call shape, including a class with no state anywhere | a receiver is recorded only when it inherits instance fields from a bootstrap-loaded class; everything else is already watched field by field |
| "write operations from 240 threads" on a 6-thread test | virtual threads are one per task and the collection detector counted ids across all 40 rounds | threads are counted per round, and a finding names the widest round |

Round two also made the read-write idiom legible: the two views of a `ReentrantReadWriteLock`
resolve to their owner, shared mode kept apart, so correct read-write usage is silent while a
write under the read view still fires. No corpus subject exposed this one; the synchronized-method
probe that found the `FileBackedOutputStream` gap found it alongside, and
`LockModelWeavingEndToEndTest` pins both directions.

The rules from the first round stand unchanged: safe publication via volatile plus locked writes,
benign constant writes, per-instance attribution, construction preceding publication, captured
inner-class fields, and retraction of fields bound to a `VarHandle` or atomic updater.

## What this does not measure

- **Thirty-four classes from three libraries is not an ecosystem study.** It is enough to bound the
  false-positive rate at the tier that gates builds, and not enough to publish a rate per detector.
- **One JVM and one OS in the table above.** The gates run on whatever CI runs; the numbers quoted
  here were taken on JDK 26 on Windows 11.
- **A zero noise column is a measurement, not a guarantee.** Fifteen documented-thread-safe
  classes now produce nothing, but each of them went quiet because a specific mechanism became
  visible to the model. A correct class guarded by a mechanism the weaver cannot see, a lock
  acquired inside unwoven code, a hand-rolled protocol on plain fields, will still draw a
  `PROMPT`-tier finding, and the tier system exists to price exactly that.
- **Detection is probabilistic, and the gate reflects that.** `CorpusGates` fails the run when a
  documented-thread-safe class draws a VERDICT-tier HIGH or CRITICAL finding, and it fails when the
  unsafe group as a whole produces nothing at all. It deliberately does not assert that a particular
  subject fires on a particular run, because that would be a flaky gate rather than a measurement.
- **The corpus classes are subjects, not endorsements.** They are on the test classpath of a
  standalone module and reach neither the reactor nor any published artifact.

**Two detectors of 142 produced every finding here.** `AtomicityValidator` reads the field stream,
`SharedCollectionDetector` reads the collection stream, and between them they are the detectors
that can speak about code the test does not record. The other 140 need the test body to tell them
what it did, which is what `AsyncTestContext` and the `AsyncAssert` surface are for. Nothing in
this run says those detectors are wrong; it says this corpus cannot measure them, and #300 is
where that gets classified rather than guessed at.

## Reproducing it

```bash
mvn install -DskipTests -Djacoco.skip=true    # the reactor, so the module can resolve 1.9.7
mvn -f corpus-eval/pom.xml test               # writes corpus-eval/target/corpus-eval/corpus-eval.md
```

The generated report carries the JVM, the OS, the configuration and the per-subject rows for that
run. The table in this document is a copy of one such run, not a second source of truth: when the
two disagree, the generated file is right and this document is stale.
