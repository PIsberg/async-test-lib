# Detector Catalog

`async-test-lib` includes **146 detectors** organized across different phases. The phase files linked below hold a categorized catalog detailing the most critical concurrency bugs detected by the library, accompanied by "Buggy Code" vs. "Fixed Code" examples.

## Detectors by phase

The numbered detector entries live in [`detector-catalog/`](detector-catalog/), grouped by phase in the order the entries were added. Severity, trust tiers and feeds stay here because every entry refers to them.

| Document | What it covers |
|----------|----------------|
| [01-foundations.md](detector-catalog/01-foundations.md) | Entries 1 to 8. Phases 1 to 3: deadlock, visibility, carrier pinning, pool deadlock, lock and ThreadLocal leaks, non-atomic collection updates, livelock |
| [02-phase-2-core-and-monitors.md](detector-catalog/02-phase-2-core-and-monitors.md) | Entries 9 to 28. Phase 2 core and monitors: false sharing, ABA, lock order, memory ordering, semaphores, CompletableFuture, SimpleDateFormat, parallel streams |
| [03-phase-2-additional-and-utilities.md](detector-catalog/03-phase-2-additional-and-utilities.md) | Entries 29 to 44. Phase 2 additional concurrency and utilities: latches, barriers, locks, double-checked locking, Phaser, StampedLock, ForkJoinPool |
| [04-hygiene-and-resources.md](detector-catalog/04-hygiene-and-resources.md) | Entries 45 to 52. Phases 3 and 4: races, busy-waiting, atomicity, interrupts, thread leaks, sleep in a lock, unbounded queues, starvation |
| [05-common-types-and-virtual-threads.md](detector-catalog/05-common-types-and-virtual-threads.md) | Entries 53 to 62. Phases 5 and 6: Calendar, shared collections, Timer, copy-on-write, StringBuilder, structured concurrency, ScopedValue, virtual threads |
| [06-patterns-lifecycle-environment.md](detector-catalog/06-patterns-lifecycle-environment.md) | Entries 63 to 81. Phases 7 to 9: HTTP clients, stream closing, caches, executor shutdown, nested monitors, InheritableThreadLocal, ConcurrentMap recursion, ForkJoinTask blocking |
| [07-types-and-operations.md](detector-catalog/07-types-and-operations.md) | Entries 82 to 96. Phases 11 and 12: Matcher, DecimalFormat, WeakReference, MessageDigest, MDC, system properties, explicit GC, deprecated thread APIs, uncaught exceptions |
| [08-categories-primitives-async.md](detector-catalog/08-categories-primitives-async.md) | Entries 97 to 110. Phases 13 to 15: daemon threads, notify, SecureRandom, JDBC connections, stateful crypto, this-escape, ThreadLocalRandom, spurious wakeups, tryLock |
| [09-stateful-jdk-and-coordination.md](detector-catalog/09-stateful-jdk-and-coordination.md) | Entries 111 to 120. Phases 17 and 19: ByteBuffer, charset coders, checksums, FileChannel position, iterators, contended atomics, JSON mappers, latches, executors, futures |
| [10-jdk-25-26.md](detector-catalog/10-jdk-25-26.md) | Entries 121 to 126. Phases 16 and 18: StableValue, StructuredTaskScope, Gatherer, LazyConstant, final-field mutation and KDF, wired into `detectAll` |
| [11-flow-and-ffm.md](detector-catalog/11-flow-and-ffm.md) | Entries 127 to 135. Phases 19 and 20: Flow publishers, confined arenas, memory segments, VarHandle, records, static-init deadlock, virtual-thread pooling, thread-per-task, SplittableRandom |
| [12-futures-scale-structured.md](detector-catalog/12-futures-scale-structured.md) | Entries 136 to 146. Phases 22 to 24: CompletableFuture publication, lambda capture, virtual-thread scale, JDK 26 scopes and lazy constants |

## Severity

Every detector states a severity, and the code is where it is stated. A detector that marks one in
its own report wins; the rest declare one in `DetectorDefaultSeverity`. Nothing is inferred any
more: until #291 a detector that wrote no marker had its severity guessed by
`IssueSeverity.fromReport`, which returned `HIGH`, and 86 of the 142 wrote none, so `failOn = HIGH`
failed on a resource left open exactly as it failed on a lost update.

The four levels mean what `IssueSeverity` defines them to mean, and that definition is what settles
an argument about a particular detector:

| Level | Definition |
|---|---|
| `CRITICAL` | Application will hang, deadlock, or crash |
| `HIGH` | Data corruption, incorrect results, or lost updates possible |
| `MEDIUM` | Performance degradation, resource leaks, or thread starvation |
| `LOW` | Minor inefficiencies or best practice violations |

Seven entries below were changed from `HIGH` to `MEDIUM` when the declarations were written,
because the catalog had drifted from that definition and from itself: it ranked one resource leak
`HIGH` and another `MEDIUM`. Leaks are `MEDIUM` by the definition above, so the leak detectors,
thread-pool health and ForkJoinTask blocking now say so. This is a real change to what a
`failOn = HIGH` gate fails on; the changelog carries the upgrade note.

`DetectorSeverityMarkerTest` enforces that every detector states a severity somewhere, that the
declaration table never shadows a detector that states its own, and that an `ADVISORY` tier
detector cannot claim `CRITICAL` or `HIGH`.

## Trust tiers

Before using any of these as a merge gate, know which kind of statement it makes. The tier is a
property of the detector, and it decides whether a finding means "your code is wrong" or "go and
check something".

| Tier | What a finding means | Use it to |
|---|---|---|
| **VERDICT** | The detector distinguishes broken code from the correctly synchronized version of that same code. Silence is informative too. | Fail a build |
| **FACT** | The report states something observed, not inferred. The claim is true; whether it is a bug in your design is your call. | Fail a build once you agree the pattern is wrong for you |
| **PROMPT** | The detector saw a pattern it cannot fully model, most often a shared object whose lock it has no way to see. Correct code that shares an object produces the same signal as a race. | Open a ticket, not fail a build |
| **ADVISORY** | A performance or hygiene note, not a correctness claim. | Read it, gate on nothing |

**The tier is in the code, not in this document.** `DetectorTrust` classifies all 146, the runner
prints the tier above every finding, every `Violation` carries it as a `trustTier` attribute, and
`@AsyncTest(minTrust = TrustTier.VERDICT)` restricts the `failOn` gate to the tiers you name.
`DetectorTrustCoverageTest` fails the build if a detector is unclassified, if a row names a
detector class the factories do not construct, or if anything reaches VERDICT without naming
both-directions evidence that resolves. This section is the narrative; the table in the code is the
authority, and the two cannot drift silently.

This is measured rather than asserted. Two evals run each covered detector against a buggy variant
*and* against a synchronized twin that records the identical event stream while holding a real
lock, and the results are published in
[analysis/detector-accuracy-eval.md](analysis/detector-accuracy-eval.md).
`DetectorAccuracyEvalTest` covers twenty detectors, one per mechanism class, with the per-detector
outcome in that document. `SharedTypeAccuracyEvalTest` covers the whole `SHARED_*` family, the 19 that watch a
non-thread-safe JDK type: 19 of 19 fire on unguarded sharing, and 17 of 19 stay silent both on the
`synchronized (instance)` twin and on a twin guarded by a declared `ReentrantLock`. The same 17
still fire when the two threads take *different* locks, which is a race no matter how many locks
are held. The twins that do fire on correct code share one cause - the guard is a lock nothing
told the library about.

**VERDICT in the code, each with both directions measured, from two sources.** Ten are backed by
tests in this repository, which the gate resolves by reflection: `DEADLOCKS`, `LOCK_ORDER`,
`ATOMIC_NON_ATOMIC_UPDATE`, `LOCK_LEAKS`, `COMPLETABLE_FUTURE_EXCEPTIONS`, `RESOURCE_LEAKS`,
`INTERRUPT_MISHANDLING`, `UNCAUGHT_EXCEPTION_HANDLER`, `COMPLETABLE_FUTURE_COMPLETION_LEAKS` and
`THREAD_LEAKS`. Nine of those ten are in the `ESSENTIALS` preset, which is the one to gate on.

Fifty-five more are backed by the corpus eval's pair lanes, where the pair is two uses of an
unmodified third-party class rather than a twin written here. Eleven were registered in the first
wave; forty-four followed on 2026-09-07, when it turned out they had been measured all along. The
promotion channel and its bar were built when the recording lane held twelve pairs; the lane grew
to 129 and nothing went back, because registering a pair is a manual step and no gate noticed one
that skipped it. The library's own gate asks whether a VERDICT has evidence and never the reverse.
So those detectors reported at PROMPT for a fortnight while a measurement that met the bar ran
green every night, and a build gated on `minTrust = VERDICT` ignored every one of them.

`EveryEligiblePairIsPromotedOrExplainedTest` in the corpus module now derives eligibility from the
rows rather than from a list, and fails until a qualifying pair is either registered or explained,
so the backlog cannot rebuild. A pair qualifies when the detector is at PROMPT, both halves name
the same class, and - in the recording lane - both reach the detector through the same
`record*`/`register*` methods, so what separates them is the state those calls carry and nothing
else. Agent-lane bodies make no such calls, the woven call sites being the input, and
`AgentRowPremise` holds them to the same property instead.
[`META-INF/async-test/verdict-evidence-corpus`](../async-test-lib/src/main/resources/META-INF/async-test/verdict-evidence-corpus)
names each pair and both modules check it, which is what keeps the tier from outliving the
measurement.

Three detectors the corpus measures in both directions stay `PROMPT`, and the reason in each case
is the detector's model rather than the pair. `CACHE_CONCURRENCY` asks the map's own type whether it
synchronizes itself, so given one class both halves of a pair get the same answer by construction,
and it consults no lock at all: a `HashMap` correctly guarded by the caller's own lock draws the
same finding as a raced one. `CONCURRENT_MAP_CHECK_THEN_ACT` is classified by its caller, because
`recordCheckThenAct` is itself the assertion that a check-then-act happened; the detector's own
decision is only whether more than one thread reached the same `(map, key)` site.
`FILE_CHANNEL_POSITION_RACE` has the better pair of the three - one shared channel, differing only
in the read overload - but `analyze()` reports on `accessingThreadIds.size() > 1` and the detector
holds no representation of a lock, so a caller who wraps `position(n)` and `read(buffer)` in
`synchronized (channel)`, which genuinely fixes the race, draws the identical finding. The remedy
is the lockset the `Shared*` family already carries, not a different pair.

**Verdict on one path, weaker on another: graded per finding.** `VAR_HANDLE_NON_ATOMIC_UPDATE`,
`STATIC_INIT_DEADLOCK`, `CONFINED_ARENA_THREAD_ESCAPE`, `RECORD_MUTABLE_COMPONENT_LEAK`,
`SHARED_MEMORY_SEGMENT_RACE`, `VIRTUAL_THREAD_POOLING` and `PLATFORM_THREAD_PER_TASK` each produce
a verdict-grade finding on one path and a prompt-grade or advisory one on another. Their detector
tier is still the weakest of those, because that is what a detector-level rating has to mean, but
their reports implement `GradedFindings` and carry a tier on each finding, so `minTrust = VERDICT`
acts on the recorded cycle, the lost update or the observed mutation without being held back by
the note beside it. Before that, a verdict-only gate stayed green on every one of them.

The grades are deliberately conservative: a finding becomes VERDICT only where
its claim is something recorded rather than inferred, such as an access after an arena closed, or
a probe reporting the thread kind a task actually ran on. Everything else stays PROMPT, which is
where it already was.

**Advisory tier:** `SHARED_RANDOM` and `SHARED_SECURE_RANDOM`. `Random` and `SecureRandom` are
thread-safe, so their finding is about contention on one instance rather than corruption of it,
and it stands whether or not you hold a lock - which is why no amount of lock awareness moves
them up a tier. `SHARED_RANDOM` used to sit at VERDICT on a corpus pair that measured only that
it separates shared from confined use; a finding about correct code cannot be a verdict, so it is
a `LOW` advisory.

`RACE_CONDITIONS` and `ATOMICITY_VIOLATIONS` moved to the split tier below: both now carry a lock
model. `RACE_CONDITIONS` intersects the lock sets held at each access to a field in a round (#570),
so a field one thread holds `{A, B}` for and another holds `{A}` for is guarded by `A` and not
reported. `ATOMICITY_VIOLATIONS` is coarser on its agent-fed path, where it compares whole lock sets
rather than intersecting them. Neither report grades its findings, so both detectors are rated
PROMPT as a whole. For `RACE_CONDITIONS` that is a decision rather than a gap: no finding it makes
can tell an unguarded access from one under an undeclared lock, and its recording API carries no
volatile or hand-off ordering, so no finding of it could honestly be graded VERDICT. Its same-class
corpus pair was read for promotion on 2026-09-14 and stays PROMPT on that model, with the reason
recorded in corpus-eval's `PairEvidence`.

**Split tier — `RACE_CONDITIONS`, `ATOMICITY_VIOLATIONS`, and the rest of the `SHARED_*`
family:** verdict for a lock the library can see, prompt for one it cannot. 17 of the 19 in that
family keep an Eraser lockset per instance - the locks held at every recorded access,
intersected - and report only once that intersection is empty. A
lock becomes visible three ways: it is the tracked instance's own monitor, so
`synchronized (theInstance)` needs nothing; the test declares it with
`AsyncTestContext.holdingLock(theLock)`, which covers a `ReentrantLock` or a private lock object;
or the agent is attached with `fields=true`, which weaves `MONITORENTER`/`MONITOREXIT` and picks
up `synchronized` blocks in woven code. An undeclared lock in unwoven code stays invisible and
still produces a finding, and so does inconsistent locking - two threads holding different locks
have excluded nothing, which is a race however many locks were involved.

**Classified, and now mostly measured.** Every detector carries a tier, because a finding with no
tier is one a reader has to rank alone. The split is 70 VERDICT, 59 PROMPT, 11 FACT and 6
ADVISORY. PROMPT is the honest default rather than a result: it says nobody has measured that
detector's silent-on-correct-code direction, not that the detector is wrong. FACT and ADVISORY are
statements about the kind of claim a finding makes rather than about missing evidence - a FACT
report says something was observed and leaves the judgement to the reader - so neither is a
weaker VERDICT and a pair does not promote them.

**Practical consequence.** Gate on the tier, not on severity alone: `failOn = HIGH` with
`minTrust = TrustTier.VERDICT` fails only on measured findings, while everything else still prints
and still reaches the JSON and SARIF output. Severity is a poor proxy for trust because most
detectors never set one: `IssueSeverity.fromReport` recovers it by matching upper-case keywords in
the report text and defaults to `HIGH`, so `failOn = HIGH` on its own is close to "fail on
anything". Without a trust floor, plan to baseline first — see [CI_INTEGRATION.md](CI_INTEGRATION.md#adopting-into-a-codebase-that-already-has-findings).

## What feeds each detector

A detector only speaks when something feeds it, and the corpus eval made the three feeds visible:
42 unmodified third-party classes under `detectAll = true` produced findings from exactly two
detectors, because only two read the agent's woven streams. Before enabling everything and
wondering about the silence, know which kind each detector is. The classification lives in
`DetectorFeeds`, the listing below mirrors it, and `DetectorFeedCoverageTest` fails the build when
the two drift or when the agent-fed set stops matching the classes the woven streams are wired
into.

### Agent-fed (20)

Read the agent's woven streams (field accesses, collection call sites, lock acquisitions) and fire
on unmodified code, third-party code included, whenever the agent is attached:

The three lock detectors and the three shared-instance detectors joined on 2026-08-27. The
agent had substituted every lock, unlock and tryLock call site since collection weaving
shipped, and handed all of it to the lockset, which answers one question: was this access
guarded. The lock three ask different questions of the same events. The shared-instance three
cover JDK types that keep mutable state, are documented as unsafe to share, and are routinely
cached in a field because building one is expensive, which is how a confined object becomes a
shared one. All six were reachable only through hand-written recording calls, so attaching the
agent and writing a plain test produced silence from them.

The coordination primitives joined last. Sharing is the point of a semaphore or a latch, so 
what these report is protocol misuse rather than sharing: a permit that never came back, an 
offer whose false return was discarded, a timed await that expired. They are plumbing, used 
three layers down in the class under test, which is why nobody instruments them by hand and 
why their detectors were unreachable in practice rather than merely inconvenient.

`MissedSignalDetector` joined with #694. `Object` exposes neither its waiters nor whether a
notify reached one, so the detector could only judge what a body recorded about itself. The
agent now substitutes `Object.wait`, `notify` and `notifyAll`, which run with the monitor held,
and marks the backward jump around a wait, which is what separates `while (!ready) wait()` from
`if (!ready) wait()`. The loop may be in the caller of the method that waits (#707). A shape that
enters `wait` before it has read the predicate, `do { wait(); } while (!ready)` among them, is not
a marked loop.

`AtomicityValidator`, `SharedCollectionDetector`, `LockOrderValidator`, `LockLeakDetector`,
`TryLockMisuseDetector`, `SimpleDateFormatDetector`, `SharedMatcherDetector`,
`SharedMessageDigestDetector`, `CalendarDetector`, `StringBuilderDetector`,
`SharedDecimalFormatDetector`, `SharedFormatterDetector`, `SemaphoreMisuseDetector`,
`CountDownLatchDetector`, `LatchMisuseDetector`, `BlockingQueueDetector`, `SleepInLockDetector`,
`MissedSignalDetector`, `ExplicitGcDetector`, `DaemonThreadHygieneDetector`

### Zero-config (3)

Watch the JVM and the harness themselves (`ThreadMXBean` deadlock scans, per-round thread-dump
snapshots, live `<clinit>` stacks) and can fire with an empty test body, no agent and no recording
call:

`DeadlockDetector`, `LivelockDetector`, `StaticInitDeadlockDetector`

### Why the rest are recording-only

Agent-feeding a detector works when one JDK method call carries everything the detector needs. That
is what the eighteen above have in common: a lock acquired, a formatter used, a permit taken. Three
things stop the others, and each was checked against the code rather than assumed.

**The detector needs a fact the substitution cannot supply.** `ExecutorShutdownDetector` reports an
executor that was registered with it and then never shut down. Registration is the load-bearing
half: a `submit` on an executor it has never seen records nothing, so feeding the agent's `submit`
and `shutdown` calls alone would leave it silent rather than wrong. Registering creation means the
`Executors` factory methods, which the static substitution path can now reach, so this one is a
decision rather than a limit - see #387.

**The call site is static.** This used to stop `SleepInLockDetector` and `ExplicitGcDetector`, and
stops neither now: the substitution has an `invokestatic` path, `Thread.sleep` was its first user
and `System.gc` its second. The second cost one table entry and one hook rather than another
visitor, which is the argument for having built the path as a mechanism. What remains is that each
static entry has to be worth its instruction, the same bar the virtual ones meet.

**The input is not a call.** `VisibilityMonitor` needs field reads and writes, which is the field
weaver's stream rather than a call site. `ThreadPoolMonitor` and the `CompletableFuture` family
need a task's start and completion, and a substituted `submit` sees neither: the task runs later,
somewhere else. `LazyInitRaceDetector` and `ThisEscapeDetector` describe a shape in the code rather
than any particular method, and no substitution can see a shape.

### Recording-only (123)

Fire only when the test body records what it did, through the detector's `record*`/`register*`
API, usually reached via `AsyncTestContext`. Attaching the agent changes nothing for these; the
recording is the feed:

`VisibilityMonitor`, `FalseSharingDetector`, `WakeupDetector`, `ConstructorSafetyValidator`,
`ABAProblemDetector`, `SynchronizerMonitor`, `ThreadPoolMonitor`,
`MemoryOrderingMonitor`, `PipelineMonitor`, `ReadWriteLockMonitor`,
`CompletableFutureExceptionDetector`, `CompletableFutureCompletionLeakDetector`,
`VirtualThreadPinningDetector`, `ThreadPoolDeadlockDetector`, `ConcurrentModificationDetector`,
`SharedRandomDetector`, `ConditionVariableDetector`,
`ParallelStreamDetector`, `ResourceLeakDetector`,
`CyclicBarrierDetector`, `ReentrantLockDetector`,
`VolatileArrayDetector`, `DoubleCheckedLockingDetector`, `WaitTimeoutDetector`,
`LockContentionDetector`, `SynchronizedNonFinalDetector`,
`LazyInitRaceDetector`, `PhaserDetector`, `StampedLockDetector`, `ExchangerDetector`,
`ScheduledExecutorDetector`, `ForkJoinPoolDetector`, `ThreadFactoryDetector`,
`RaceConditionDetector`, `ThreadLocalMonitor`, `BusyWaitDetector`, `InterruptMonitor`,
`ThreadLeakDetector`, `UnboundedQueueDetector`, `ThreadStarvationDetector`,
`TimerDetector`, `CopyOnWriteCollectionDetector`,
`StructuredConcurrencyMisuseDetector`, `VirtualThreadContextLeakDetector`,
`ScopedValueMisuseDetector`, `VirtualThreadCpuBoundTaskDetector`,
`VirtualThreadCarrierExhaustionDetector`, `HttpClientConcurrencyDetector`, `StreamClosingDetector`,
`CacheConcurrencyDetector`, `CompletableFutureChainDetector`, `ExecutorShutdownDetector`,
`MutableMapKeyDetector`, `NestedMonitorLockoutDetector`, `LockDowngradeDetector`,
`InheritableThreadLocalMisuseDetector`, `ThreadLocalContaminationDetector`,
`AtomicNonAtomicUpdateDetector`, `SynchronizedCollectionIterationDetector`,
`ConcurrentMapComputeRecursionDetector`,
`SynchronizedOnLiteralDetector`, `PublicLockExposureDetector`, `ForkJoinTaskBlockingDetector`,
`OptimisticReadValidationDetector`, `CompletableFutureCommonPoolBlockingDetector`,
`WeakReferenceRaceDetector`,
`StatefulLambdaDetector`, `InterruptSwallowingDetector`,
`MdcContextLeakDetector`, `SystemPropertyMutationDetector`, `FutureIgnoredDetector`,
`DeprecatedThreadApiDetector`, `SharedXmlParserDetector`,
`BoxedPrimitiveLockDetector`, `SharedTimeZoneDetector`, `UncaughtExceptionHandlerDetector`,
`NotifyWithoutMonitorDetector`, `SharedSecureRandomDetector`,
`WeakHashMapSharedDetector`, `JdbcConnectionSharedDetector`, `SharedStatefulCryptoDetector`,
`NonAtomicConcurrentMapUpdateDetector`, `SharedDeflaterDetector`, `ThisEscapeDetector`,
`ThreadLocalRandomMisuseDetector`, `CompletableFutureObtrudeDetector`, `SpuriousWakeupDetector`,
`LockUpgradeDeadlockDetector`,
`CompletableFutureBlockingCallbackDetector`, `StableValueMisuseDetector`,
`StructuredTaskScopeMisuseDetector`, `GathererConcurrencyMisuseDetector`,
`SharedByteBufferDetector`, `SharedCharsetCoderDetector`, `SharedChecksumDetector`,
`FileChannelPositionRaceDetector`, `SharedIteratorDetector`, `HighContentionAtomicDetector`,
`SharedJsonMapperReconfigDetector`, `LazyConstantMisuseDetector`, `FinalFieldMutationDetector`,
`SharedKdfDetector`, `ExecutorDeadlockDetector`, `FutureBlockingDetector`,
`FlowPublisherConcurrencyDetector`, `ConfinedArenaThreadEscapeDetector`,
`SharedMemorySegmentRaceDetector`, `VarHandleNonAtomicUpdateDetector`,
`RecordMutableComponentLeakDetector`, `VirtualThreadPoolingDetector`,
`PlatformThreadPerTaskDetector`, `SharedSplittableRandomDetector`,
`CompletableFutureCompletionRaceDetector`, `CompletableFutureCancellationPropagationDetector`,
`CompletableFutureCombinatorMisuseDetector`, `LambdaLostUpdateDetector`,
`VirtualThreadResourceSaturationDetector`, `VirtualThreadMonitorSerializationDetector`,
`ThreadLocalCacheDegradationDetector`, `ScopeJoinerMisuseDetector`,
`ScopeConfigurationMisuseDetector`, `ScopeResultEscapeDetector`, `LazyCollectionMisuseDetector`
