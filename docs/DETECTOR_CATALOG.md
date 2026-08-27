# Detector Catalog

`async-test-lib` includes **146 detectors** organized across different phases. Below is a categorized catalog detailing the most critical concurrency bugs detected by the library, accompanied by "Buggy Code" vs. "Fixed Code" examples.

---

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
both-directions tests that exist. This section is the narrative; the table in the code is the
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

**VERDICT in the code, each with both directions measured:** `DEADLOCKS`, `LOCK_ORDER`,
`ATOMIC_NON_ATOMIC_UPDATE`, `LOCK_LEAKS`, `COMPLETABLE_FUTURE_EXCEPTIONS`, `RESOURCE_LEAKS`,
`INTERRUPT_MISHANDLING`, `UNCAUGHT_EXCEPTION_HANDLER`, `COMPLETABLE_FUTURE_COMPLETION_LEAKS` and
`THREAD_LEAKS`. Each names a test that fires on the bug and a test that stays silent on the
correct twin, and the gate resolves both by reflection. Nine of the ten are in the `ESSENTIALS`
preset, which is the one to gate on.

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

**Prompt tier:** `SHARED_RANDOM` and `SHARED_SECURE_RANDOM`. `Random` and `SecureRandom` are
thread-safe, so their finding is about contention on one instance rather than corruption of it,
and it stands whether or not you hold a lock - which is why no amount of lock awareness moves
them up a tier.

`RACE_CONDITIONS` and `ATOMICITY_VIOLATIONS` moved to the split tier below: both now carry a lock
model, `ATOMICITY_VIOLATIONS` a coarser one on its agent-fed path (it compares whole lock sets
rather than intersecting them, so a field one thread holds `{A, B}` for and another holds `{A}`
for is still reported).

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

**Classified, but not all measured.** Every detector now carries a tier, because a finding with no
tier is one a reader has to rank alone. Most carry PROMPT, which is the honest default rather than
a result: it says nobody has measured that detector's silent-on-correct-code direction, not that
the detector is wrong. The two evals measure 33 distinct detectors of 146 between them (three
appear in both), and extending them is mechanical rather than hard. Each new both-directions case
either promotes a detector or writes down a limit, and both outcomes are worth having: the
`CONCURRENT_MODIFICATIONS` pair, added with the tier mechanism, showed the detector firing on two
threads appending to a `CopyOnWriteArrayList`, which is correct code with no iterator in sight.

**Practical consequence.** Gate on the tier, not on severity alone: `failOn = HIGH` with
`minTrust = TrustTier.VERDICT` fails only on measured findings, while everything else still prints
and still reaches the JSON and SARIF output. Severity is a poor proxy for trust because most
detectors never set one: `IssueSeverity.fromReport` recovers it by matching upper-case keywords in
the report text and defaults to `HIGH`, so `failOn = HIGH` on its own is close to "fail on
anything". Without a trust floor, plan to baseline first — see [CI_INTEGRATION.md](CI_INTEGRATION.md#adopting-into-a-codebase-that-already-has-findings).

---

## What feeds each detector

A detector only speaks when something feeds it, and the corpus eval made the three feeds visible:
42 unmodified third-party classes under `detectAll = true` produced findings from exactly two
detectors, because only two read the agent's woven streams. Before enabling everything and
wondering about the silence, know which kind each detector is. The classification lives in
`DetectorFeeds`, the listing below mirrors it, and `DetectorFeedCoverageTest` fails the build when
the two drift or when the agent-fed set stops matching the classes the woven streams are wired
into.

### Agent-fed (12)

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

`AtomicityValidator`, `SharedCollectionDetector`, `LockOrderValidator`, `LockLeakDetector`,
`TryLockMisuseDetector`, `SimpleDateFormatDetector`, `SharedMatcherDetector`,
`SharedMessageDigestDetector`, `CalendarDetector`, `StringBuilderDetector`,
`SharedDecimalFormatDetector`, `SharedFormatterDetector`

### Zero-config (3)

Watch the JVM and the harness themselves (`ThreadMXBean` deadlock scans, per-round thread-dump
snapshots, live `<clinit>` stacks) and can fire with an empty test body, no agent and no recording
call:

`DeadlockDetector`, `LivelockDetector`, `StaticInitDeadlockDetector`

### Recording-only (131)

Fire only when the test body records what it did, through the detector's `record*`/`register*`
API, usually reached via `AsyncTestContext`. Attaching the agent changes nothing for these; the
recording is the feed:

`VisibilityMonitor`, `FalseSharingDetector`, `WakeupDetector`, `ConstructorSafetyValidator`,
`ABAProblemDetector`, `SynchronizerMonitor`, `ThreadPoolMonitor`,
`MemoryOrderingMonitor`, `PipelineMonitor`, `ReadWriteLockMonitor`, `SemaphoreMisuseDetector`,
`CompletableFutureExceptionDetector`, `CompletableFutureCompletionLeakDetector`,
`VirtualThreadPinningDetector`, `ThreadPoolDeadlockDetector`, `ConcurrentModificationDetector`,
`SharedRandomDetector`, `BlockingQueueDetector`, `ConditionVariableDetector`,
`ParallelStreamDetector`, `ResourceLeakDetector`,
`CountDownLatchDetector`, `CyclicBarrierDetector`, `ReentrantLockDetector`,
`VolatileArrayDetector`, `DoubleCheckedLockingDetector`, `WaitTimeoutDetector`,
`LockContentionDetector`, `SynchronizedNonFinalDetector`, `MissedSignalDetector`,
`LazyInitRaceDetector`, `PhaserDetector`, `StampedLockDetector`, `ExchangerDetector`,
`ScheduledExecutorDetector`, `ForkJoinPoolDetector`, `ThreadFactoryDetector`,
`RaceConditionDetector`, `ThreadLocalMonitor`, `BusyWaitDetector`, `InterruptMonitor`,
`ThreadLeakDetector`, `SleepInLockDetector`, `UnboundedQueueDetector`, `ThreadStarvationDetector`,
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
`ExplicitGcDetector`, `DeprecatedThreadApiDetector`, `SharedXmlParserDetector`,
`BoxedPrimitiveLockDetector`, `SharedTimeZoneDetector`, `UncaughtExceptionHandlerDetector`,
`DaemonThreadHygieneDetector`, `NotifyWithoutMonitorDetector`, `SharedSecureRandomDetector`,
`WeakHashMapSharedDetector`, `JdbcConnectionSharedDetector`, `SharedStatefulCryptoDetector`,
`NonAtomicConcurrentMapUpdateDetector`, `SharedDeflaterDetector`, `ThisEscapeDetector`,
`ThreadLocalRandomMisuseDetector`, `CompletableFutureObtrudeDetector`, `SpuriousWakeupDetector`,
`LockUpgradeDeadlockDetector`,
`CompletableFutureBlockingCallbackDetector`, `StableValueMisuseDetector`,
`StructuredTaskScopeMisuseDetector`, `GathererConcurrencyMisuseDetector`,
`SharedByteBufferDetector`, `SharedCharsetCoderDetector`, `SharedChecksumDetector`,
`FileChannelPositionRaceDetector`, `SharedIteratorDetector`, `HighContentionAtomicDetector`,
`SharedJsonMapperReconfigDetector`, `LazyConstantMisuseDetector`, `FinalFieldMutationDetector`,
`SharedKdfDetector`, `LatchMisuseDetector`, `ExecutorDeadlockDetector`, `FutureBlockingDetector`,
`FlowPublisherConcurrencyDetector`, `ConfinedArenaThreadEscapeDetector`,
`SharedMemorySegmentRaceDetector`, `VarHandleNonAtomicUpdateDetector`,
`RecordMutableComponentLeakDetector`, `VirtualThreadPoolingDetector`,
`PlatformThreadPerTaskDetector`, `SharedSplittableRandomDetector`,
`CompletableFutureCompletionRaceDetector`, `CompletableFutureCancellationPropagationDetector`,
`CompletableFutureCombinatorMisuseDetector`, `LambdaLostUpdateDetector`,
`VirtualThreadResourceSaturationDetector`, `VirtualThreadMonitorSerializationDetector`,
`ThreadLocalCacheDegradationDetector`, `ScopeJoinerMisuseDetector`,
`ScopeConfigurationMisuseDetector`, `ScopeResultEscapeDetector`, `LazyCollectionMisuseDetector`

---

## Phase 1: Core (Always Enabled)

These detectors run automatically on every `@AsyncTest` without configuration.

### 1. Deadlock Detector
* **Severity**: `CRITICAL`
* **Description**: Detects circular dependencies between threads waiting on monitors or reentrant locks. Two sources, because one is not enough: `ThreadMXBean.findDeadlockedThreads()` covers platform threads, and the JVM's own JSON thread dump covers virtual ones, which the JMX query never reports and which are what `@AsyncTest` runs its workers on by default. The second source needs a JDK whose dump names the monitors each thread holds and is blocked on - measured present on 26, absent on 21 and 24. Where it is absent a clean report means the question could not be asked, and the runner says so at INFO (`runner.detector.inert`); `useVirtualThreads = false` gets the finding on any JDK. Monitors only: a `ReentrantLock` deadlock parks rather than blocks, and the dump names the blocker but not its owner.
* **Buggy Code**:
  ```java
  // Thread A
  synchronized (lockA) {
      synchronized (lockB) {
          // work
      }
  }
  // Thread B
  synchronized (lockB) {
      synchronized (lockA) {
          // work
      }
  }
  ```
* **Fixed Code**:
  ```java
  // Establish a strict global lock acquisition order (always lockA then lockB)
  synchronized (lockA) {
      synchronized (lockB) {
          // work
      }
  }
  ```

### 2. Visibility & Memory Model Detector
* **Severity**: `HIGH`
* **Description**: Identifies fields updated across threads without a happens-before relationship (missing `volatile` or memory barrier).
* **Buggy Code**:
  ```java
  class FlagHolder {
      private boolean ready = false; // Missing volatile
      
      void setReady() { ready = true; }
      void checkReady() {
          while (!ready) { /* spin */ }
      }
  }
  ```
* **Fixed Code**:
  ```java
  class FlagHolder {
      private volatile boolean ready = false; // Volatile guarantees cross-thread visibility
      
      void setReady() { ready = true; }
      void checkReady() {
          while (!ready) { Thread.onSpinWait(); }
      }
  }
  ```

---

## Phase 2: Advanced Concurrency Monitors

Monitors that track synchronizer usage, thread pools, and Loom (virtual threads).

### 3. Virtual Thread Carrier Pinning Detector
* **Severity**: `HIGH`
* **Description**: Flags virtual threads that block inside `synchronized` blocks or native methods, locking the underlying carrier thread.
* **Buggy Code**:
  ```java
  private final Object monitor = new Object();

  void doWork() {
      synchronized (monitor) { // Blocks the carrier thread when running under virtual threads
          Thread.sleep(Duration.ofMillis(100));
      }
  }
  ```
* **Fixed Code**:
  ```java
  private final ReentrantLock lock = new ReentrantLock();

  void doWork() {
      lock.lock(); // Virtual-thread friendly blocking
      try {
          Thread.sleep(Duration.ofMillis(100));
      } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
      } finally {
          lock.unlock();
      }
  }
  ```

### 4. Thread Pool Deadlock Detector
* **Severity**: `CRITICAL`
* **Description**: Detects tasks blocking on nested tasks submitted to the same thread-bounded executor pool (pool starvation deadlock).
* **Buggy Code**:
  ```java
  ExecutorService pool = Executors.newFixedThreadPool(2);
  
  void executeTask() throws Exception {
      pool.submit(() -> {
          // Inner task submitted to same pool
          Future<String> nested = pool.submit(() -> "data");
          return nested.get(); // deadlocks if pool is fully saturated
      }).get();
  }
  ```
* **Fixed Code**:
  ```java
  // Use separate executors for orchestrators vs worker tasks, or use asynchronous chaining
  CompletableFuture.supplyAsync(() -> "data", workerPool)
      .thenAcceptAsync(result -> process(result), orchestratorPool);
  ```

### 5. Lock Leak Detector
* **Severity**: `HIGH`
* **Description**: Flags locks acquired but not guaranteed to be released on all execution paths.
* **Buggy Code**:
  ```java
  void doLockedWork() {
      lock.lock();
      doSomethingThatMightThrow(); // If throws, lock is leaked forever
      lock.unlock();
  }
  ```
* **Fixed Code**:
  ```java
  void doLockedWork() {
      lock.lock();
      try {
          doSomethingThatMightThrow();
      } finally {
          lock.unlock(); // Guaranteed to release
      }
  }
  ```

---

## Phase 3: Behavioral & Runtime Hygiene

Detectors that observe unsafe usages of JDK classes and concurrent collections.

### 6. ThreadLocal Leak Detector
* **Severity**: `MEDIUM`
* **Description**: Detects `ThreadLocal` variables set during execution but not cleaned up, causing memory leaks in recycled thread pools.
* **Buggy Code**:
  ```java
  private static final ThreadLocal<UserContext> CTX = new ThreadLocal<>();
  
  void process(UserContext context) {
      CTX.set(context);
      executeBusinessLogic();
      // Context left in thread local
  }
  ```
* **Fixed Code**:
  ```java
  private static final ThreadLocal<UserContext> CTX = new ThreadLocal<>();
  
  void process(UserContext context) {
      CTX.set(context);
      try {
          executeBusinessLogic();
      } finally {
          CTX.remove(); // Clean up thread local context
      }
  }
  ```

### 7. Non-Atomic Update on Concurrent Collections
* **Severity**: `HIGH`
* **Description**: Flags check-then-act operations on `ConcurrentHashMap` that bypass its thread-safety guarantees.
* **Buggy Code**:
  ```java
  ConcurrentHashMap<String, List<String>> map = new ConcurrentHashMap<>();
  
  void addValue(String key, String val) {
      if (!map.containsKey(key)) { // Race condition: multiple threads can enter block
          map.put(key, new ArrayList<>());
      }
      map.get(key).add(val);
  }
  ```
* **Fixed Code**:
  ```java
  ConcurrentHashMap<String, List<String>> map = new ConcurrentHashMap<>();
  
  void addValue(String key, String val) {
      // Use atomic computeIfAbsent
      map.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(val);
  }
  ```

---

## Phase 1 (cont.): Core Concurrency

### 8. Livelock Detector
* **Severity**: `CRITICAL`
* **Description**: Reports two things, and the name promises a third it does not deliver. It reports **starvation** (a thread whose recent snapshots are all BLOCKED or WAITING with flat CPU time) and **rapid state cycling** (five state changes in ten snapshots). It does **not** report a busy spin: `madeProgress()` treats any RUNNABLE thread as making progress, deliberately, because a busy worker's measured CPU time can look flat when several snapshots land inside one clock tick and reporting those produced findings against healthy JVMs. So a spin-retry loop burning attempts without completing work - which is what livelock usually means - is not a finding here; `LivelockDetectorTest` pins that. Samples via `ThreadMXBean.dumpAllThreads`, which does not report virtual threads, so on the default `@AsyncTest` runner nothing reaches its history at all and the runner announces `runner.detector.inert`. See issues #362, #367 and #373.
* **Buggy Code**:
  ```java
  // Two threads politely "back off" forever, never making progress
  while (!tryAcquire(resource)) {
      yieldToOther();     // both threads keep yielding to each other
      Thread.onSpinWait();
  }
  ```
* **Fixed Code**:
  ```java
  // Randomized backoff breaks the symmetry so one thread eventually wins
  Random jitter = ThreadLocalRandom.current();
  while (!tryAcquire(resource)) {
      Thread.sleep(jitter.nextInt(1, 10));
  }
  ```

---

## Phase 2: Core

### 9. False Sharing Detector
* **Severity**: `MEDIUM`
* **Status**: **Experimental - findings off by default.** Cache-line effects are not observable from pure Java: the detector estimates offsets by summing nominal type sizes in declaration order, while the JVM reorders fields, compresses references, and honors `@Contended` padding, so the estimated offsets do not correspond to real memory layout. Its reports are therefore not evidence of false sharing. Enable with `-Dasync-test.experimental.false-sharing=true`; without the property, `analyze()` returns an empty report (recording is unaffected).
* **Description**: Flags fields accessed by different threads whose estimated memory offsets fall within the same CPU cache line (64 bytes), which causes cache-coherency traffic and performance degradation even without a logical data race.
* **Buggy Code**:
  ```java
  class Counters {
      volatile long counterA; // adjacent fields share a cache line
      volatile long counterB; // updated by a different thread
  }
  ```
* **Fixed Code**:
  ```java
  class Counters {
      volatile long counterA;
      long p1, p2, p3, p4, p5, p6, p7; // padding pushes counterB to its own cache line
      volatile long counterB;
  }
  ```

### 10. Wakeup Issues Detector
* **Severity**: `HIGH`
* **Description**: Tracks `wait()`/`notify()` pairs per monitor to catch spurious wakeups (a thread resumes without being notified) and lost notifications (`notify()` fires while no thread is waiting), both of which are hard to reproduce and debug.
* **Buggy Code**:
  ```java
  synchronized (lock) {
      if (!conditionMet) {
          lock.wait(); // no waiter yet when notify() races in -> notification lost
      }
  }
  ```
* **Fixed Code**:
  ```java
  synchronized (lock) {
      while (!conditionMet) { // re-check in a loop to survive spurious wakeups
          lock.wait();
      }
  }
  ```

### 11. Constructor Safety Detector
* **Severity**: `HIGH`
* **Description**: Tracks object construction start/end and cross-thread field access to catch unsafe publication — objects shared with other threads before their constructor completes can expose partially initialized fields due to compiler/CPU reordering.
* **Buggy Code**:
  ```java
  class Publisher {
      Publisher() {
          this.data = computeData();
          GLOBAL_REGISTRY.put(id, this); // 'this' escapes before construction finishes
      }
  }
  ```
* **Fixed Code**:
  ```java
  class Publisher {
      private Publisher() { this.data = computeData(); }
      static Publisher create() {
          Publisher p = new Publisher();     // fully constructed first
          GLOBAL_REGISTRY.put(p.id, p);      // published only after completion
          return p;
      }
  }
  ```

### 12. ABA Problem Detector
* **Severity**: `CRITICAL`
* **Description**: Detects the ABA problem in lock-free CAS-based code, where a value changes from A to B and back to A between a thread's read and its `compareAndSet`, causing the CAS to spuriously succeed and corrupt the data structure.
* **Buggy Code**:
  ```java
  Node head = stack.get();
  Node next = head.next;
  // another thread pops head, pushes a new node that reuses the same reference
  stack.compareAndSet(head, next); // succeeds even though the stack changed underneath
  ```
* **Fixed Code**:
  ```java
  AtomicStampedReference<Node> stack = ...;
  int[] stamp = new int[1];
  Node head = stack.get(stamp);
  Node next = head.next;
  stack.compareAndSet(head, next, stamp[0], stamp[0] + 1); // stamp detects the A->B->A cycle
  ```

### 13. Lock Order Detector
* **Severity**: `CRITICAL`
* **Description**: Records the sequence in which each thread acquires locks and flags inconsistent orderings across threads, which is the classic precondition for a deadlock even when no deadlock has actually occurred yet during the run.
* **Buggy Code**:
  ```java
  // Thread A
  synchronized (accountA) { synchronized (accountB) { transfer(); } }
  // Thread B
  synchronized (accountB) { synchronized (accountA) { transfer(); } } // reversed order
  ```
* **Fixed Code**:
  ```java
  // Always acquire locks in a globally consistent order (e.g. by identity hash)
  Object first = System.identityHashCode(accountA) < System.identityHashCode(accountB) ? accountA : accountB;
  Object second = (first == accountA) ? accountB : accountA;
  synchronized (first) { synchronized (second) { transfer(); } }
  ```

### 14. Synchronizer Misuse Detector
* **Severity**: `CRITICAL`
* **Description**: Monitors `CyclicBarrier`, `Phaser`, and `CountDownLatch` usage to detect parties advancing asynchronously, phasers advancing without all participants arriving, and barriers being reset while threads are still waiting on them.
* **Buggy Code**:
  ```java
  CyclicBarrier barrier = new CyclicBarrier(4);
  // Only 3 of 4 worker threads ever call await() due to an early-return bug
  barrier.await(); // the 4th never arrives -> the other 3 wait forever
  ```
* **Fixed Code**:
  ```java
  CyclicBarrier barrier = new CyclicBarrier(4, () -> onAllPartiesArrived());
  try {
      barrier.await(5, TimeUnit.SECONDS); // bounded wait surfaces the stuck party instead of hanging
  } catch (TimeoutException e) {
      barrier.reset(); // releases waiting parties with a BrokenBarrierException
  }
  ```

### 15. Thread Pool Health Detector
* **Severity**: `MEDIUM`
* **Description**: Tracks executor queue depth, task rejections, active-thread counts, and per-task duration to surface pool saturation, silent task rejection, and worker starvation before they manifest as user-visible timeouts.
* **Buggy Code**:
  ```java
  ExecutorService pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(10));
  pool.execute(task); // RejectedExecutionException silently kills the task under load
  ```
* **Fixed Code**:
  ```java
  ExecutorService pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(10),
      new ThreadPoolExecutor.CallerRunsPolicy()); // caller absorbs overflow instead of losing tasks
  ```

### 16. Memory Ordering Detector
* **Severity**: `HIGH`
* **Description**: Logs reads and writes per memory location and thread to detect visibility violations — reads that observe stale values after a write from another thread, or writes that appear reordered due to missing happens-before edges.
* **Buggy Code**:
  ```java
  class Holder {
      int value;
      boolean ready; // plain field: no happens-before edge to readers
      void publish() { value = 42; ready = true; }
      void consume() { if (ready) use(value); } // may see ready=true but value=0
  }
  ```
* **Fixed Code**:
  ```java
  class Holder {
      volatile int value;
      volatile boolean ready; // volatile write/read establishes happens-before
      void publish() { value = 42; ready = true; }
      void consume() { if (ready) use(value); }
  }
  ```

### 17. Async Pipeline Detector
* **Severity**: `HIGH`
* **Description**: Tracks published/processed/failed event counts per named pipeline stage and reports the "unaccounted" difference, catching events that are silently dropped between publish and processing in multi-stage async flows.
* **Buggy Code**:
  ```java
  void onEvent(Event e) {
      if (!filter.test(e)) {
          return; // event silently dropped, never counted as processed or failed
      }
      downstream.publish(e);
  }
  ```
* **Fixed Code**:
  ```java
  void onEvent(Event e) {
      if (!filter.test(e)) {
          pipelineMonitor.recordEventFailed(stageName, e.id(), "filtered"); // accounted for
          return;
      }
      downstream.publish(e);
  }
  ```

### 18. Read-Write Lock Fairness Detector
* **Severity**: `MEDIUM`
* **Description**: Measures per-lock read/write acquisition counts and wait times to detect writer starvation, where a steady stream of readers keeps a non-fair `ReadWriteLock`'s writer waiting indefinitely.
* **Buggy Code**:
  ```java
  ReadWriteLock rw = new ReentrantReadWriteLock(); // default: non-fair, favors readers
  // many short-lived reader threads keep re-acquiring the read lock;
  // the writer's writeLock().lock() can starve indefinitely
  ```
* **Fixed Code**:
  ```java
  ReadWriteLock rw = new ReentrantReadWriteLock(true); // fair mode grants access roughly in arrival order
  ```

---

## Phase 2: Monitors

### 19. Semaphore Misuse Detector
* **Severity**: `HIGH`
* **Description**: Tracks `acquire()`/`release()` pairs per `Semaphore` to catch permit leaks (acquire without a matching release), over-release (releasing more permits than were acquired), and permits still outstanding at test completion.
* **Buggy Code**:
  ```java
  semaphore.acquire();
  doWork(); // if this throws, release() below never runs -> permit leaked forever
  semaphore.release();
  ```
* **Fixed Code**:
  ```java
  semaphore.acquire();
  try {
      doWork();
  } finally {
      semaphore.release(); // always released, even on exception
  }
  ```

### 20. CompletableFuture Exception Detector
* **Severity**: `HIGH`
* **Description**: Monitors `CompletableFuture` chains for exception-handling gaps — futures that complete exceptionally without a registered handler, or that are joined/gotten without any `exceptionally()`/`handle()` in the chain, which can silently swallow failures.
* **Buggy Code**:
  ```java
  CompletableFuture.supplyAsync(() -> riskyCall())
      .thenApply(this::transform); // no exceptionally()/handle(): failure vanishes silently
  ```
* **Fixed Code**:
  ```java
  CompletableFuture.supplyAsync(() -> riskyCall())
      .thenApply(this::transform)
      .exceptionally(ex -> { log.error("pipeline failed", ex); return fallback(); });
  ```

### 21. CompletableFuture Completion Leak Detector
* **Severity**: `HIGH`
* **Description**: Registers manually-created `CompletableFuture` instances and flags ones that are never completed on any code path, a common source of indefinitely hanging `get()`/`join()` calls and thread-pool starvation.
* **Buggy Code**:
  ```java
  CompletableFuture<String> future = new CompletableFuture<>();
  try {
      future.complete(doWork());
  } catch (Exception e) {
      log.error("failed", e); // exception path forgets to complete the future -> caller hangs forever
  }
  ```
* **Fixed Code**:
  ```java
  CompletableFuture<String> future = new CompletableFuture<>();
  try {
      future.complete(doWork());
  } catch (Exception e) {
      future.completeExceptionally(e); // every path completes the future
  }
  ```

### 22. Concurrent Modification Detector
* **Severity**: `HIGH`
* **Description**: Registers collections and tracks active iterators against concurrent modifications, flagging unsafe structural changes made during iteration over non-thread-safe collections instead of via `Iterator.remove()`.
* **Buggy Code**:
  ```java
  for (String item : sharedList) {
      if (shouldRemove(item)) {
          sharedList.remove(item); // structural modification during iteration
      }
  }
  ```
* **Fixed Code**:
  ```java
  Iterator<String> it = sharedList.iterator();
  while (it.hasNext()) {
      if (shouldRemove(it.next())) {
          it.remove(); // safe removal via the iterator itself
      }
  }
  ```

### 23. Shared Random Detector
* **Severity**: `MEDIUM`
* **Description**: Tracks concurrent access to a single `Random` instance across threads, flagging contention on its internal atomic seed that degrades throughput even though `java.util.Random` itself remains thread-safe.
* **Buggy Code**:
  ```java
  static final Random random = new Random();
  int roll() { return random.nextInt(6); } // all threads contend on one seed's CAS loop
  ```
* **Fixed Code**:
  ```java
  int roll() { return ThreadLocalRandom.current().nextInt(6); } // per-thread generator, no contention
  ```

### 24. Blocking Queue Detector
* **Severity**: `HIGH`
* **Description**: Instruments `BlockingQueue` `offer`/`poll`/`put`/`take` calls to catch silently ignored return values, queue saturation, unbounded growth, and producer/consumer throughput imbalance.
* **Buggy Code**:
  ```java
  queue.offer(item); // return value ignored: item is silently dropped if the queue is full
  ```
* **Fixed Code**:
  ```java
  if (!queue.offer(item, 500, TimeUnit.MILLISECONDS)) {
      handleBackpressure(item); // failure is observed and handled instead of dropped
  }
  ```

### 25. Condition Variable Detector
* **Severity**: `HIGH`
* **Description**: Monitors `Lock.Condition` `await()`/`signal()` pairs to catch signals fired with no waiters, signals lost before the corresponding `await()`, and `await()` calls made outside a while-loop guard that are vulnerable to spurious wakeups.
* **Buggy Code**:
  ```java
  lock.lock();
  try {
      if (!dataReady) {
          condition.await(); // if() instead of while(): vulnerable to spurious wakeup
      }
  } finally { lock.unlock(); }
  ```
* **Fixed Code**:
  ```java
  lock.lock();
  try {
      while (!dataReady) { // re-checks the predicate after every wakeup
          condition.await();
      }
  } finally { lock.unlock(); }
  ```

### 26. SimpleDateFormat Sharing Detector
* **Severity**: `HIGH`
* **Description**: Tracks concurrent `format()`/`parse()` calls on a single `SimpleDateFormat` instance, which is not thread-safe and can silently corrupt its internal `Calendar` state under concurrent access instead of throwing.
* **Buggy Code**:
  ```java
  static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd");
  String format(Date d) { return SDF.format(d); } // shared mutable Calendar -> corrupted output
  ```
* **Fixed Code**:
  ```java
  static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd"); // immutable, thread-safe
  String format(LocalDate d) { return FMT.format(d); }
  ```

### 27. Parallel Stream Detector
* **Severity**: `HIGH`
* **Description**: Tracks parallel stream operations for stateful lambdas that mutate captured external state, non-thread-safe collectors, and non-associative reduce functions, all of which produce races or wrong results only under parallel execution.
* **Buggy Code**:
  ```java
  List<Integer> results = new ArrayList<>(); // not thread-safe
  list.parallelStream().forEach(results::add); // concurrent structural modification
  ```
* **Fixed Code**:
  ```java
  List<Integer> results = list.parallelStream()
      .collect(Collectors.toList()); // built-in collector handles thread-safety internally
  ```

### 28. Resource Leak Detector
* **Severity**: `MEDIUM`
* **Description**: Tracks open/close counts for `AutoCloseable` resources (streams, connections) across threads to catch resources opened but never closed, especially on exception paths that skip cleanup.
* **Buggy Code**:
  ```java
  FileInputStream fis = new FileInputStream("data.txt");
  fis.read(); // if read() throws, close() below never runs -> file handle leaked
  fis.close();
  ```
* **Fixed Code**:
  ```java
  try (FileInputStream fis = new FileInputStream("data.txt")) {
      fis.read(); // try-with-resources guarantees close() even on exception
  }
  ```

---

## Phase 2: Additional Concurrency

### 29. CountDownLatch Misuse Detector
* **Severity**: `HIGH`
* **Description**: Flags CountDownLatch misuse: `await()` calls that time out, latches whose count never reaches zero because `countDown()` is missing on some path, extra `countDown()` calls beyond the initial count, and attempts to reuse a single-use latch.
* **Buggy Code**:
  ```java
  CountDownLatch latch = new CountDownLatch(3);
  Runnable worker = () -> {
      if (shouldSkip()) return; // early return skips countDown()
      doWork();
      latch.countDown();
  };
  executor.submit(worker);
  executor.submit(worker);
  executor.submit(worker);
  latch.await(); // blocks forever if any worker took the early-return path
  ```
* **Fixed Code**:
  ```java
  CountDownLatch latch = new CountDownLatch(3);
  Runnable worker = () -> {
      try {
          if (shouldSkip()) return;
          doWork();
      } finally {
          latch.countDown(); // always released, even on early return
      }
  };
  executor.submit(worker);
  executor.submit(worker);
  executor.submit(worker);
  latch.await(5, TimeUnit.SECONDS); // bounded wait with timeout
  ```

### 30. CyclicBarrier Misuse Detector
* **Severity**: `HIGH`
* **Description**: Detects CyclicBarrier misuse: `await()` timeouts, barriers broken by interruption or timeout, reuse of a broken barrier without an intervening `reset()`, and threads that fail to show up for a cycle.
* **Buggy Code**:
  ```java
  CyclicBarrier barrier = new CyclicBarrier(3);
  executor.submit(() -> {
      try {
          barrier.await(100, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
          // barrier is now broken; other parties get BrokenBarrierException
      }
      barrier.await(); // reused without reset() - throws immediately
  });
  ```
* **Fixed Code**:
  ```java
  CyclicBarrier barrier = new CyclicBarrier(3);
  executor.submit(() -> {
      try {
          barrier.await(5, TimeUnit.SECONDS);
      } catch (TimeoutException | BrokenBarrierException e) {
          barrier.reset(); // repair the barrier before any thread reuses it
      }
  });
  ```

### 31. ReentrantLock Misuse Detector
* **Severity**: `HIGH`
* **Description**: Detects ReentrantLock misuse: excessive thread wait times (starvation), unfair acquisition ordering, `tryLock()` timeouts, and locks not released inside a `finally` block.
* **Buggy Code**:
  ```java
  ReentrantLock lock = new ReentrantLock(); // unfair by default
  void doWork() {
      lock.lock();
      riskyOperation(); // if this throws, lock is never released
      lock.unlock();
  }
  ```
* **Fixed Code**:
  ```java
  ReentrantLock lock = new ReentrantLock(true); // fair ordering
  void doWork() throws InterruptedException {
      if (lock.tryLock(2, TimeUnit.SECONDS)) {
          try {
              riskyOperation();
          } finally {
              lock.unlock(); // guaranteed release
          }
      }
  }
  ```

### 32. Volatile Array Detector
* **Severity**: `HIGH`
* **Description**: Flags the misconception that `volatile` on an array reference makes its elements volatile too — only reassignment of the reference is visible across threads, not writes to individual elements, so element updates can be invisible to other threads.
* **Buggy Code**:
  ```java
  private volatile int[] counters = new int[10];

  void increment(int index) {
      counters[index]++; // element write is NOT volatile - may not be visible
  }
  ```
* **Fixed Code**:
  ```java
  private final AtomicIntegerArray counters = new AtomicIntegerArray(10);

  void increment(int index) {
      counters.incrementAndGet(index); // atomic, visible to all threads
  }
  ```

### 33. Double-Checked Locking Detector
* **Severity**: `HIGH`
* **Description**: Detects the classic broken double-checked-locking idiom, where a lazily-initialized field checked both outside and inside a synchronized block is not declared `volatile`, allowing other threads to observe a partially constructed instance.
* **Buggy Code**:
  ```java
  private Instance instance; // NOT volatile

  Instance getInstance() {
      if (instance == null) {
          synchronized (lock) {
              if (instance == null) {
                  instance = new Instance(); // may publish partially-built object
              }
          }
      }
      return instance;
  }
  ```
* **Fixed Code**:
  ```java
  private volatile Instance instance; // volatile establishes happens-before

  Instance getInstance() {
      if (instance == null) {
          synchronized (lock) {
              if (instance == null) {
                  instance = new Instance();
              }
          }
      }
      return instance;
  }
  ```

### 34. Wait Timeout Detector
* **Severity**: `HIGH`
* **Description**: Flags `wait()` calls made without a timeout, which block indefinitely if a signal is lost or never sent; recommends the timed overload so a stuck waiter can recover.
* **Buggy Code**:
  ```java
  synchronized (lock) {
      while (!condition) {
          lock.wait(); // no timeout - blocks forever if signal is lost
      }
  }
  ```
* **Fixed Code**:
  ```java
  synchronized (lock) {
      while (!condition) {
          lock.wait(1000); // timeout allows periodic recheck and recovery
      }
  }
  ```

### 35. Lock Contention Detector
* **Severity**: `MEDIUM`
* **Description**: Monitors acquire attempts versus contended acquires per monitor and flags "hot locks" where more than 20% of attempts had to wait, indicating a throughput-limiting bottleneck.
* **Buggy Code**:
  ```java
  private final Object sharedLock = new Object();

  void hotPath() {
      synchronized (sharedLock) { // single lock shared by all 8 worker threads
          expensiveComputation();
      }
  }
  ```
* **Fixed Code**:
  ```java
  private final Object[] shards = new Object[16];

  void hotPath(int key) {
      synchronized (shards[key % shards.length]) { // striped locking reduces contention
          expensiveComputation();
      }
  }
  ```

### 36. Synchronized on Non-Final Field Detector
* **Severity**: `HIGH`
* **Description**: Flags synchronizing on a lock field that is not `final`, since a reassignment mid-flight lets different threads synchronize on different object instances, providing no real mutual exclusion.
* **Buggy Code**:
  ```java
  private Object lock = new Object(); // not final - can be reassigned

  void doWork() {
      synchronized (lock) { // thread may hold a different lock than another thread
          criticalSection();
      }
      lock = new Object(); // reassignment breaks mutual exclusion
  }
  ```
* **Fixed Code**:
  ```java
  private final Object lock = new Object(); // final - identity never changes

  void doWork() {
      synchronized (lock) {
          criticalSection();
      }
  }
  ```

### 37. Missed Signal Detector
* **Severity**: `CRITICAL`
* **Description**: Detects lost/missed signals where `notify()`/`notifyAll()` fires while no thread is yet waiting on the condition, so the wakeup is silently discarded and the eventual waiter blocks forever.
* **Buggy Code**:
  ```java
  // Thread A (producer, runs first)
  synchronized (monitor) {
      dataReady = true;
      monitor.notify(); // signal lost - no one is waiting yet
  }

  // Thread B (consumer, runs second)
  synchronized (monitor) {
      while (!dataReady) {
          monitor.wait(); // blocks forever - missed the earlier notify
      }
  }
  ```
* **Fixed Code**:
  ```java
  // The dataReady flag closes the race window regardless of arrival order
  synchronized (monitor) {
      dataReady = true;
      monitor.notifyAll();
  }

  synchronized (monitor) {
      while (!dataReady) { // re-checked even if wait() is entered after the notify
          monitor.wait(1000);
      }
  }
  ```

### 38. Lazy Initialization Race Detector
* **Severity**: `HIGH`
* **Description**: Detects lazy-init races where multiple threads observe a non-volatile field as `null` simultaneously and each proceeds to construct it, causing duplicate initialization and possible visibility inconsistency.
* **Buggy Code**:
  ```java
  private ExpensiveObject instance; // not volatile, no synchronization

  ExpensiveObject getInstance() {
      if (instance == null) {           // Thread A and B both see null
          instance = new ExpensiveObject(); // both threads initialize!
      }
      return instance;
  }
  ```
* **Fixed Code**:
  ```java
  private final AtomicReference<ExpensiveObject> instance = new AtomicReference<>();

  ExpensiveObject getInstance() {
      return instance.updateAndGet(v -> v != null ? v : new ExpensiveObject());
  }
  ```

---

## Phase 2: Advanced Concurrency Utilities

### 39. Phaser Misuse Detector
* **Severity**: `HIGH`
* **Description**: Detects Phaser misuse: parties that never call `arrive()` so the phaser never advances, `awaitAdvance()` timeouts, unexpected phaser termination, and a registered-party count that doesn't match observed arrivals.
* **Buggy Code**:
  ```java
  Phaser phaser = new Phaser(3);
  executor.submit(() -> {
      doWork();
      if (shouldSkip()) return; // forgot arrive() - other 2 parties block forever
      phaser.arriveAndAwaitAdvance();
  });
  ```
* **Fixed Code**:
  ```java
  Phaser phaser = new Phaser(3);
  executor.submit(() -> {
      try {
          doWork();
      } finally {
          phaser.arriveAndAwaitAdvance(); // always arrives, even on early return
      }
  });
  ```

### 40. StampedLock Misuse Detector
* **Severity**: `HIGH`
* **Description**: Detects StampedLock misuse: optimistic-read stamps used without a subsequent `validate()` call, unsafe upgrade attempts from optimistic to write mode, stamps not released in a `finally` block, and unlocking with the wrong stamp.
* **Buggy Code**:
  ```java
  long stamp = lock.tryOptimisticRead();
  int x = data;
  int y = otherData;
  // no validate() - x/y may be inconsistent if a writer ran concurrently
  return x + y;
  ```
* **Fixed Code**:
  ```java
  long stamp = lock.tryOptimisticRead();
  int x = data;
  int y = otherData;
  if (!lock.validate(stamp)) {
      stamp = lock.readLock();
      try {
          x = data;
          y = otherData;
      } finally {
          lock.unlockRead(stamp);
      }
  }
  return x + y;
  ```

### 41. Exchanger Misuse Detector
* **Severity**: `HIGH`
* **Description**: Detects Exchanger misuse: `exchange()` timeouts, an odd number of participating threads that leaves one partner permanently unmatched, interruptions during exchange, and `null` values passed through the exchange.
* **Buggy Code**:
  ```java
  Exchanger<Buffer> exchanger = new Exchanger<>();
  // Only 3 threads submitted for a pairwise exchange - one never finds a partner
  for (int i = 0; i < 3; i++) {
      executor.submit(() -> exchanger.exchange(myBuffer)); // last thread blocks forever
  }
  ```
* **Fixed Code**:
  ```java
  Exchanger<Buffer> exchanger = new Exchanger<>();
  // Threads submitted in matched pairs, with a bounded wait
  for (int i = 0; i < 4; i++) {
      executor.submit(() -> exchanger.exchange(myBuffer, 5, TimeUnit.SECONDS));
  }
  ```

### 42. Scheduled Executor Detector
* **Severity**: `HIGH`
* **Description**: Detects ScheduledExecutorService misuse: schedulers that are never shut down, confusion between fixed-delay and fixed-rate scheduling, long-running tasks that starve the scheduler thread, and unhandled exceptions inside scheduled tasks.
* **Buggy Code**:
  ```java
  ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
  scheduler.scheduleAtFixedRate(() -> {
      blockingIoCall(); // takes 5s, starves the single scheduler thread
  }, 0, 1, TimeUnit.SECONDS);
  // scheduler is never shut down - JVM can't exit cleanly
  ```
* **Fixed Code**:
  ```java
  ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
  scheduler.scheduleWithFixedDelay(() -> {
      try {
          blockingIoCall();
      } catch (Exception e) {
          log.error("scheduled task failed", e); // handled, doesn't silently die
      }
  }, 0, 1, TimeUnit.SECONDS);
  scheduler.shutdown(); // always released
  ```

### 43. ForkJoinPool Misuse Detector
* **Severity**: `HIGH`
* **Description**: Detects ForkJoinPool misuse: tasks forked but never joined, `RecursiveTask` implementations that don't return a result, pools sized too small for the workload (starvation), and exceptions swallowed inside forked tasks.
* **Buggy Code**:
  ```java
  class Sum extends RecursiveTask<Long> {
      protected Long compute() {
          Sum left = new Sum(...);
          left.fork(); // forked...
          // ...but never joined - result and thread are silently abandoned
          return computeRight();
      }
  }
  ```
* **Fixed Code**:
  ```java
  class Sum extends RecursiveTask<Long> {
      protected Long compute() {
          Sum left = new Sum(...);
          left.fork();
          long rightResult = computeRight();
          return left.join() + rightResult; // always joined
      }
  }
  ```

### 44. Thread Factory Detector
* **Severity**: `MEDIUM`
* **Description**: Detects ThreadFactory hygiene issues: threads created without an `UncaughtExceptionHandler`, non-daemon threads left in pools that block JVM shutdown, and threads left with the default `Thread-N` name that hampers diagnostics.
* **Buggy Code**:
  ```java
  ThreadFactory factory = r -> new Thread(r); // no name, no daemon flag, no exception handler
  ```
* **Fixed Code**:
  ```java
  ThreadFactory factory = r -> {
      Thread t = new Thread(r, "worker-pool-" + counter.incrementAndGet());
      t.setDaemon(true);
      t.setUncaughtExceptionHandler((thread, ex) -> log.error("uncaught in {}", thread.getName(), ex));
      return t;
  };
  ```

---

## Phase 3: Behavioral & Runtime Hygiene (cont.)

### 45. Race Condition Detector
* **Severity**: `HIGH`
* **Description**: Tracks reads and writes to the same field of the same object instance across threads and flags fields observed with concurrent read/write or write/write access from more than one thread without synchronization.
* **Buggy Code**:
  ```java
  class Counter {
      private int total; // no volatile, no lock

      void add(int value) { total += value; } // read-modify-write race across threads
      int get() { return total; }
  }
  ```
* **Fixed Code**:
  ```java
  class Counter {
      private final AtomicInteger total = new AtomicInteger();

      void add(int value) { total.addAndGet(value); } // atomic RMW, no lost updates
      int get() { return total.get(); }
  }
  ```

### 46. Busy-Waiting Detector
* **Severity**: `MEDIUM`
* **Description**: Tracks tight spin loops that run for more than 10,000 iterations before yielding or blocking, flagging CPU-burning polling loops that should instead park, sleep, or use a blocking primitive.
* **Buggy Code**:
  ```java
  while (!ready) {
      // tight spin - burns 100% CPU on this core until ready flips
  }
  ```
* **Fixed Code**:
  ```java
  while (!ready) {
      LockSupport.parkNanos(1_000_000); // yields the CPU between checks
  }
  ```

### 47. Atomicity Violation Detector
* **Severity**: `HIGH`
* **Description**: Tracks compound "read-check-then-act" operations bracketed by start/end markers and cross-references concurrent field accesses observed from other threads mid-operation, flagging sequences that were assumed atomic but were not.
* **Buggy Code**:
  ```java
  if (map.containsKey(key)) {      // read
      // another thread may remove/replace `key` right here
      Value v = map.get(key);
      map.put(key, v.increment()); // act - based on a now-stale read
  }
  ```
* **Fixed Code**:
  ```java
  map.compute(key, (k, v) -> v == null ? initial() : v.increment()); // single atomic step
  ```

### 48. Interrupt Mishandling Detector
* **Severity**: `HIGH`
* **Description**: Tracks caught `InterruptedException`s and whether the interrupt status was subsequently restored via `Thread.currentThread().interrupt()` or silently discarded, which breaks cooperative task cancellation for callers further up the stack.
* **Buggy Code**:
  ```java
  try {
      Thread.sleep(1000);
  } catch (InterruptedException e) {
      // swallowed - caller has no idea the thread was asked to stop
  }
  ```
* **Fixed Code**:
  ```java
  try {
      Thread.sleep(1000);
  } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // restore status for upstream cancellation checks
  }
  ```

---

## Phase 4: Infrastructure & Resource Management

### 49. Thread Leak Detector
* **Severity**: `MEDIUM`
* **Description**: Tracks thread creation and termination, then reports threads that were started but never joined or interrupted by test completion, or unexplained `Thread.activeCount()` growth across invocations.
* **Buggy Code**:
  ```java
  void startWorker() {
      Thread t = new Thread(() -> {
          while (!Thread.interrupted()) { poll(); }
      });
      t.start(); // never stored, never joined, never interrupted
  }
  ```
* **Fixed Code**:
  ```java
  private final Thread worker = new Thread(() -> {
      while (!Thread.interrupted()) { poll(); }
  });

  void startWorker() { worker.start(); }
  void stopWorker() throws InterruptedException {
      worker.interrupt();
      worker.join(); // ensures the thread actually terminates
  }
  ```

### 50. Sleep-in-Lock Detector
* **Severity**: `MEDIUM`
* **Description**: Uses stack-trace sampling to flag `Thread.sleep()` calls made while holding a monitor or `ReentrantLock`, which needlessly extends lock-hold time, worsens contention, and risks priority inversion or deadlock.
* **Buggy Code**:
  ```java
  synchronized (lock) {
      doWork();
      Thread.sleep(100); // holds the lock the entire time it sleeps
  }
  ```
* **Fixed Code**:
  ```java
  doWork();
  Thread.sleep(100); // sleep outside the critical section
  synchronized (lock) {
      doWork2();
  }
  ```

### 51. Unbounded Queue Detector
* **Severity**: `MEDIUM`
* **Description**: Flags `BlockingQueue`s created without a capacity bound (and unbounded thread-pool executors), and tracks queue-size growth beyond a configurable threshold, since unbounded growth under producer/consumer imbalance leads to `OutOfMemoryError`.
* **Buggy Code**:
  ```java
  BlockingQueue<Task> queue = new LinkedBlockingQueue<>(); // unbounded!
  executor.submit(() -> {
      while (true) { queue.put(produceTask()); } // grows without limit if consumer is slow
  });
  ```
* **Fixed Code**:
  ```java
  BlockingQueue<Task> queue = new LinkedBlockingQueue<>(1000); // bounded
  executor.submit(() -> {
      while (true) { queue.put(produceTask()); } // blocks producer once full, applying backpressure
  });
  ```

### 52. Thread Starvation Detector
* **Severity**: `MEDIUM`
* **Description**: Tracks task submission and start times per executor and reports tasks that waited excessively long before execution, indicating an undersized pool, long-running tasks monopolizing threads, or unfair scheduling.
* **Buggy Code**:
  ```java
  ExecutorService executor = Executors.newFixedThreadPool(1); // one thread, many tasks
  for (int i = 0; i < 100; i++) {
      executor.submit(() -> blockingIoCall()); // tasks pile up, later ones starve
  }
  ```
* **Fixed Code**:
  ```java
  ExecutorService executor = Executors.newFixedThreadPool(
      Runtime.getRuntime().availableProcessors() * 2); // sized for the workload
  for (int i = 0; i < 100; i++) {
      executor.submit(() -> blockingIoCall());
  }
  ```

---

## Phase 5: Thread-Safety of Common Types

### 53. Calendar Sharing Detector
* **Severity**: `MEDIUM`
* **Description**: `Calendar` is not thread-safe; concurrent `get()`/`set()`/`add()`/`getTime()` calls on a shared instance can interleave and silently corrupt the represented date with no exception thrown. The detector tracks shared registrations and flags mutation-during-read contention.
* **Buggy Code**:
  ```java
  private static final Calendar SHARED_CAL = Calendar.getInstance();

  void formatDate(int year) {
      SHARED_CAL.set(Calendar.YEAR, year); // mutated concurrently by many threads
      process(SHARED_CAL.getTime());       // may observe another thread's year
  }
  ```
* **Fixed Code**:
  ```java
  void formatDate(int year) {
      Calendar local = Calendar.getInstance(); // one instance per call/thread
      local.set(Calendar.YEAR, year);
      process(local.getTime());
  }
  ```

### 54. Shared Collection Detector
* **Severity**: `HIGH`
* **Description**: Flags plain `ArrayList`, `HashMap`/`LinkedHashMap`, `HashSet`, `LinkedList`, `TreeMap`/`TreeSet`, and `ArrayDeque` instances mutated from multiple threads without synchronization, which can corrupt internal state or throw `ConcurrentModificationException`.
* **Buggy Code**:
  ```java
  private static final List<String> SHARED = new ArrayList<>();

  void record(String event) {
      SHARED.add(event); // concurrent structural modification -> corruption/CME
  }
  ```
* **Fixed Code**:
  ```java
  private static final List<String> SHARED = new CopyOnWriteArrayList<>();
  // or: Collections.synchronizedList(new ArrayList<>())

  void record(String event) {
      SHARED.add(event); // thread-safe
  }
  ```

### 55. Timer Sharing Detector
* **Severity**: `HIGH`
* **Description**: `java.util.Timer` runs all scheduled tasks on a single thread, and an uncaught exception in any `TimerTask` kills that thread — silently cancelling every remaining scheduled task with no error reported. The detector flags long-running tasks, failures, and post-cancellation scheduling attempts.
* **Buggy Code**:
  ```java
  Timer timer = new Timer("worker");
  timer.schedule(new TimerTask() {
      public void run() {
          doWork(); // if this throws, the Timer thread dies silently,
      }             // cancelling ALL future scheduled tasks
  }, 0, 100);
  ```
* **Fixed Code**:
  ```java
  ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();
  ses.scheduleAtFixedRate(() -> {
      try {
          doWork();
      } catch (Exception e) {
          log.error("task failed", e); // isolated; future runs still scheduled
      }
  }, 0, 100, TimeUnit.MILLISECONDS);
  ```

### 56. Copy-On-Write Collection Misuse Detector
* **Severity**: `MEDIUM`
* **Description**: `CopyOnWriteArrayList`/`CopyOnWriteArraySet` are correct under concurrency but copy the entire backing array on every write; the detector flags write-heavy usage (write ratio above a configurable threshold) where the O(n)-per-write cost becomes a bottleneck better served by another concurrent structure.
* **Buggy Code**:
  ```java
  CopyOnWriteArrayList<Event> events = new CopyOnWriteArrayList<>();

  void onEvent(Event e) {
      events.add(e); // O(n) full-array copy on every write, called constantly
  }
  ```
* **Fixed Code**:
  ```java
  Set<Event> events = ConcurrentHashMap.newKeySet(); // O(1) add/remove for write-heavy use

  void onEvent(Event e) {
      events.add(e);
  }
  ```

### 57. Shared StringBuilder Detector
* **Severity**: `HIGH`
* **Description**: `StringBuilder` is explicitly not thread-safe; concurrent `append()`/`insert()`/`delete()`/`replace()` on a shared instance can garble output, throw `StringIndexOutOfBoundsException`, or silently drop characters. The detector tracks registered builders and flags concurrent mutating access.
* **Buggy Code**:
  ```java
  private static final StringBuilder LOG = new StringBuilder();

  void append(String entry) {
      LOG.append(entry); // concurrent append can corrupt internal char[] / throw
  }
  ```
* **Fixed Code**:
  ```java
  private static final ThreadLocal<StringBuilder> LOG = ThreadLocal.withInitial(StringBuilder::new);

  void append(String entry) {
      LOG.get().append(entry); // one builder per thread, joined at the end
  }
  ```

---

## Phase 6: Virtual Thread Concurrency (Java 21+)

### 58. Structured Concurrency Misuse Detector
* **Severity**: `HIGH`
* **Description**: Detects violations of the `StructuredTaskScope` discipline that subtasks must not outlive their scope: scopes left unclosed (resource leak), subtask results read via `get()` before `join()` (stale/incorrect data), excessive scope nesting, and scopes opened with zero forked subtasks.
* **Buggy Code**:
  ```java
  try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
      Subtask<String> a = scope.fork(() -> fetch());
      String result = a.get(); // BUG: read before join() -> stale/incorrect
      scope.join();
  }
  ```
* **Fixed Code**:
  ```java
  try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
      Subtask<String> a = scope.fork(() -> fetch());
      scope.join();            // wait for all subtasks first
      scope.throwIfFailed();
      String result = a.get(); // safe now
  }
  ```

### 59. Virtual Thread Context Leak Detector
* **Severity**: `HIGH`
* **Description**: Flags `ThreadLocal` values set on a virtual thread but never removed before it completes — since virtual threads and their carriers are reused/pooled by the JVM, stale values can leak into an unrelated later task. Also flags `InheritableThreadLocal` usage (not propagated to virtual threads) and excessive distinct `ThreadLocal` counts per thread.
* **Buggy Code**:
  ```java
  private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

  void handle(String id) {
      REQUEST_ID.set(id);
      process(); // never removed -> leaks to a future task on a reused thread
  }
  ```
* **Fixed Code**:
  ```java
  void handle(String id) {
      REQUEST_ID.set(id);
      try {
          process();
      } finally {
          REQUEST_ID.remove(); // guaranteed cleanup before thread completes
      }
  }
  ```

### 60. ScopedValue Misuse Detector
* **Severity**: `CRITICAL`
* **Description**: Detects misuse of Java 21+ `ScopedValue`, the recommended `ThreadLocal` replacement for virtual threads: conflicting rebind attempts via nested `where().run()`, cross-scope access (`get()` outside any binding, throwing `NoSuchElementException`), and an excessive number of distinct bindings per call chain.
* **Buggy Code**:
  ```java
  static final ScopedValue<String> USER_ID = ScopedValue.newInstance();

  void handle() {
      String id = USER_ID.get(); // BUG: get() called with no enclosing where().run() binding
  }
  ```
* **Fixed Code**:
  ```java
  void handle(String user) {
      ScopedValue.where(USER_ID, user).run(() -> {
          String id = USER_ID.get(); // safe: inside the binding
          process(id);
      });
  }
  ```

### 61. Virtual Thread CPU-Bound Task Detector
* **Severity**: `MEDIUM`
* **Description**: Virtual threads are designed for I/O-bound work that parks cheaply; CPU-bound tasks monopolize the carrier thread for their entire duration, negating the scalability benefit. The detector flags individual tasks that run past a duration threshold without a recorded yield point, and a high mean duration across recorded tasks.
* **Buggy Code**:
  ```java
  @AsyncTest(threads = 200, useVirtualThreads = true)
  void testMatrixMultiply() {
      performHeavyComputation(); // CPU-bound work monopolizes the carrier thread
  }
  ```
* **Fixed Code**:
  ```java
  @AsyncTest(threads = 200) // plain platform threads for CPU-bound work
  void testMatrixMultiply() {
      performHeavyComputation();
  }
  ```

### 62. Virtual Thread Carrier Exhaustion Detector
* **Severity**: `HIGH`
* **Description**: When many virtual threads are simultaneously pinned (e.g. inside `synchronized`) or otherwise blocked in a way that can't unmount them from their carrier, all carrier threads in the scheduler's `ForkJoinPool` can become occupied — apparent deadlock/starvation with no classic deadlock cycle. The detector tracks peak and sustained concurrently-blocked counts against the carrier thread count.
* **Buggy Code**:
  ```java
  private final Object lock = new Object();

  void handle() { // 20 virtual threads, few carrier threads
      synchronized (lock) { // pins the carrier; many concurrent pins exhaust the pool
          Thread.sleep(10);
      }
  }
  ```
* **Fixed Code**:
  ```java
  private final ReentrantLock lock = new ReentrantLock();

  void handle() {
      lock.lock();
      try {
          Thread.sleep(10); // unmounts the virtual thread instead of pinning the carrier
      } finally {
          lock.unlock();
      }
  }
  ```

---

## Phase 7: High-Level Concurrency Patterns

### 63. HTTP Client Concurrency Detector
* **Severity**: `HIGH`
* **Description**: Flags concurrency issues around Java 11+ `HttpClient` usage: unclosed/unconsumed response bodies, connection-pool exhaustion from too many concurrent requests, unsafe concurrent access to a shared `HttpClient`, and requests that are initiated but never awaited.
* **Buggy Code**:
  ```java
  HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
  // response body stream never consumed/closed -> connection not returned to pool
  ```
* **Fixed Code**:
  ```java
  HttpResponse<InputStream> response = client.send(request, BodyHandlers.ofInputStream());
  try (InputStream body = response.body()) {
      consume(body); // ensures the underlying connection is released
  }
  ```

### 64. Stream Closing Detector
* **Severity**: `MEDIUM`
* **Description**: Detects `InputStream`/`OutputStream`/`Reader`/`Writer` instances that are opened but never closed, closed from a different thread than the one that opened them, left open in excessive numbers concurrently, or not managed via try-with-resources.
* **Buggy Code**:
  ```java
  InputStream is = new FileInputStream("data.txt");
  readAll(is);
  // is.close() never called -> file descriptor leak
  ```
* **Fixed Code**:
  ```java
  try (InputStream is = new FileInputStream("data.txt")) {
      readAll(is); // try-with-resources guarantees close()
  }
  ```

### 65. Cache Concurrency Detector
* **Severity**: `HIGH`
* **Description**: Detects unsynchronized `HashMap`/`LinkedHashMap`-backed caches accessed from multiple threads: mutation during iteration, read-write races producing stale reads or lost updates, and cache stampede where multiple threads recompute the same value simultaneously.
* **Buggy Code**:
  ```java
  Map<String, Object> cache = new HashMap<>(); // not thread-safe

  Object get(String key) {
      if (!cache.containsKey(key)) {
          cache.put(key, compute(key)); // races with concurrent readers/writers
      }
      return cache.get(key);
  }
  ```
* **Fixed Code**:
  ```java
  Map<String, Object> cache = new ConcurrentHashMap<>();

  Object get(String key) {
      return cache.computeIfAbsent(key, this::compute); // atomic, avoids stampede
  }
  ```

### 66. CompletableFuture Chain Detector
* **Severity**: `HIGH`
* **Description**: Flags improper `CompletableFuture` chain usage: missing `.exceptionally()`/`.handle()` handlers, futures created but never joined/awaited, chained stages without exception propagation, and blocking `.join()`/`.get()` calls that starve the same thread pool used for the async work.
* **Buggy Code**:
  ```java
  CompletableFuture.supplyAsync(() -> riskyCall())
      .thenApply(String::toUpperCase); // no .exceptionally()/.handle(); result never joined
  ```
* **Fixed Code**:
  ```java
  CompletableFuture.supplyAsync(() -> riskyCall())
      .thenApply(String::toUpperCase)
      .exceptionally(ex -> { log.error("failed", ex); return "fallback"; })
      .join(); // exception handled, chain awaited
  ```

---

## Phase 8: Lifecycle & Structural Correctness

### 67. Executor Shutdown Detector
* **Severity**: `MEDIUM`
* **Description**: Flags `ExecutorService` instances that have tasks submitted but are never shut down (thread leak), or that are shut down without a following `awaitTermination()` call (submitted tasks may be silently abandoned or still running at test end).
* **Buggy Code**:
  ```java
  ExecutorService pool = Executors.newFixedThreadPool(4);
  pool.submit(() -> doWork());
  // pool.shutdown() never called -> pool threads leak forever
  ```
* **Fixed Code**:
  ```java
  ExecutorService pool = Executors.newFixedThreadPool(4);
  try {
      pool.submit(() -> doWork());
  } finally {
      pool.shutdown();
      pool.awaitTermination(30, TimeUnit.SECONDS);
  }
  ```

### 68. Mutable Map Key Detector
* **Severity**: `HIGH`
* **Description**: Flags mutable objects used as `HashMap`/`HashSet` keys that are mutated after insertion, breaking the `equals()`/`hashCode()` stability the collection contract requires — the key ends up stored in the wrong hash bucket and lookups/removals silently fail.
* **Buggy Code**:
  ```java
  class MutableKey { String name; /* equals/hashCode based on name */ }

  MutableKey key = new MutableKey("initial");
  map.put(key, "value");
  key.name = "mutated"; // BUG: rehashes silently break lookup/remove
  map.get(key); // may return null even though key "is" in the map
  ```
* **Fixed Code**:
  ```java
  record ImmutableKey(String name) {} // final fields; equals/hashCode stable forever

  ImmutableKey key = new ImmutableKey("initial");
  map.put(key, "value");
  map.get(key); // always finds it - key never changes after insertion
  ```

### 69. Nested Monitor Lockout Detector
* **Severity**: `CRITICAL`
* **Description**: Detects the nested-monitor-lockout anti-pattern — performing a blocking operation (`Object.wait()`, `Future.get()`, `Lock.lock()`) while holding a monitor on a different object — which can deadlock two threads in a way invisible to a thread dump, and otherwise degrades throughput by holding a coarse lock across a blocking call.
* **Buggy Code**:
  ```java
  synchronized (lockA) {
      result = future.get(); // BUG: blocking call while holding lockA -> deadlock risk
  }
  ```
* **Fixed Code**:
  ```java
  Future<String> f = future; // resolve outside the monitor
  result = f.get();
  synchronized (lockA) {
      use(result); // lockA never held during a blocking operation
  }
  ```

### 70. Lock Downgrade Detector
* **Severity**: `CRITICAL`
* **Description**: Flags the unsafe `ReentrantReadWriteLock` downgrade: a thread that releases the write lock and then acquires the read lock leaves a gap in which another thread can write, so the read need not return what the writer wrote. The finding is evidence-gated and reported only when another thread was observed taking the write lock inside that gap, because the shape alone is also what correct code produces when a thread writes one thing and later reads another. The correct write-then-read downgrade is not flagged. It also observes the read-to-write upgrade, but when `LockUpgradeDeadlockDetector` is enabled - which `detectAll` does - the observations are forwarded there and reported under that name instead, so one upgrade is one finding. With this detector alone, it reports the upgrade itself. See issue #361.
* **Buggy Code**:
  ```java
  rwLock.readLock().lock();
  try {
      rwLock.writeLock().lock(); // BUG: read-to-write upgrade -> immediate deadlock
  } finally {
      rwLock.readLock().unlock();
  }
  ```
* **Fixed Code**:
  ```java
  rwLock.writeLock().lock();    // acquire write first
  try {
      rwLock.readLock().lock(); // downgrade: acquire read while still holding write
  } finally {
      rwLock.writeLock().unlock(); // release write, keep read
  }
  ```

### 71. InheritableThreadLocal Misuse Detector
* **Severity**: `HIGH`
* **Description**: `InheritableThreadLocal` copies parent-thread values into a child thread at thread-creation time, not task-submission time; in a thread pool the worker threads are created once and reused, so every pooled task inherits whatever values were set when the pool was created — leaking request-scoped user IDs, transaction context, or locale across unrelated tasks.
* **Buggy Code**:
  ```java
  static final InheritableThreadLocal<String> USER = new InheritableThreadLocal<>();

  void handleRequest(String user) {
      USER.set(user);
      pool.submit(() -> process()); // pooled thread was created once, then reused
  }                                  // by a different request, inheriting stale USER
  ```
* **Fixed Code**:
  ```java
  void handleRequest(String user) {
      pool.submit(() -> {
          USER.set(user); // set explicitly per task, not inherited at pool creation
          try {
              process();
          } finally {
              USER.remove();
          }
      });
  }
  ```

---

## Phase 9: Repository & Environment State

### 72. ThreadLocal Contamination Detector
* **Severity**: `HIGH`
* **Description**: Detects `ThreadLocal` values that bleed from one pooled-thread task into the next task reusing the same thread. Unlike a plain memory leak, this is a correctness bug — request-scoped state such as MDC loggers or security contexts silently becomes visible to an unrelated later task.
* **Buggy Code**:
  ```java
  private static final ThreadLocal<UserContext> CTX = new ThreadLocal<>();

  void handleRequest(UserContext context) {
      CTX.set(context);
      executeBusinessLogic(); // reads CTX
      // no cleanup: next task on this pooled thread inherits this context
  }
  ```
* **Fixed Code**:
  ```java
  private static final ThreadLocal<UserContext> CTX = new ThreadLocal<>();

  void handleRequest(UserContext context) {
      CTX.set(context);
      try {
          executeBusinessLogic();
      } finally {
          CTX.remove(); // prevents contamination of the next pooled task
      }
  }
  ```

### 73. Atomic/Non-Atomic Update Mixing Detector
* **Severity**: `HIGH`
* **Description**: Detects non-atomic compound updates on `AtomicInteger`/`AtomicLong`/`AtomicReference` — a `get()` followed by a later `set()` instead of `compareAndSet()`/`updateAndGet()`. The per-operation atomicity of the Atomic* classes does not make the surrounding read-modify-write sequence atomic, so concurrent updates are silently lost.
* **Buggy Code**:
  ```java
  AtomicInteger counter = new AtomicInteger();

  void increment() {
      int v = counter.get();
      // another thread may increment here
      counter.set(v + 1); // BUG: overwrites concurrent updates
  }
  ```
* **Fixed Code**:
  ```java
  AtomicInteger counter = new AtomicInteger();

  void increment() {
      counter.updateAndGet(v -> v + 1); // atomic read-modify-write
  }
  ```

### 74. Synchronized Collection Iteration Detector
* **Severity**: `HIGH`
* **Description**: Detects iteration over `Collections.synchronizedList`/`synchronizedMap`/`synchronizedSet` wrappers without holding the wrapper's own intrinsic lock, as the JDK Javadoc requires. Unsynchronized iteration allows a concurrent modification to throw `ConcurrentModificationException` or silently skip elements.
* **Buggy Code**:
  ```java
  List<String> list = Collections.synchronizedList(new ArrayList<>());

  void printAll() {
      for (String s : list) { // BUG: no synchronized(list) block
          System.out.println(s);
      }
  }
  ```
* **Fixed Code**:
  ```java
  List<String> list = Collections.synchronizedList(new ArrayList<>());

  void printAll() {
      synchronized (list) { // required by the wrapper's contract
          for (String s : list) {
              System.out.println(s);
          }
      }
  }
  ```

### 75. Shared Formatter Detector
* **Severity**: `HIGH`
* **Description**: Detects `java.util.Formatter`, `PrintWriter`, and `PrintStream` instances (including `System.out`/`System.err`) accessed concurrently from multiple threads without external synchronization. These classes are not thread-safe, so concurrent use interleaves output or corrupts internal formatting state.
* **Buggy Code**:
  ```java
  PrintWriter sharedWriter = new PrintWriter(outputStream);

  void logLine(String msg) {
      sharedWriter.format("[%s] %s%n", Instant.now(), msg); // unsynchronized concurrent access
  }
  ```
* **Fixed Code**:
  ```java
  private final Object writerLock = new Object();
  PrintWriter sharedWriter = new PrintWriter(outputStream);

  void logLine(String msg) {
      synchronized (writerLock) {
          sharedWriter.format("[%s] %s%n", Instant.now(), msg);
      }
  }
  ```

### 76. ConcurrentMap Compute Recursion Detector
* **Severity**: `HIGH`
* **Description**: Detects recursive calls to `ConcurrentHashMap.computeIfAbsent`/`compute`/`merge` on the same map and key from within the mapping function itself — a well-known JDK footgun that infinite-loops on Java 8 and throws `IllegalStateException` on Java 9+, most commonly triggered by naive recursive memoization.
* **Buggy Code**:
  ```java
  ConcurrentHashMap<String, Integer> cache = new ConcurrentHashMap<>();

  int memoize(String key) {
      return cache.computeIfAbsent(key,
          k -> cache.computeIfAbsent(k, this::expensiveLoad)); // BUG: recursive compute on same map/key
  }
  ```
* **Fixed Code**:
  ```java
  ConcurrentHashMap<String, Integer> cache = new ConcurrentHashMap<>();

  int memoize(String key) {
      Integer existing = cache.get(key);
      if (existing != null) return existing;
      return cache.computeIfAbsent(key, this::expensiveLoad); // single, non-recursive compute
  }
  ```

### 77. Synchronized-on-Literal Detector
* **Severity**: `HIGH`
* **Description**: Detects `synchronized` blocks locking on interned `String` literals or JVM-cached boxed `Integer`/`Long` values (`-128..127`). Because the JVM shares these instances, unrelated classes synchronizing on the same literal or small integer share a single JVM-wide monitor, causing silent cross-module lock coupling and potential deadlock.
* **Buggy Code**:
  ```java
  void doWork() {
      synchronized ("shared-lock") { // interned literal: JVM-wide monitor
          // unrelated code elsewhere may synchronize on the same literal
      }
  }
  ```
* **Fixed Code**:
  ```java
  private final Object lock = new Object(); // unique, private monitor

  void doWork() {
      synchronized (lock) {
          // safely scoped to this class only
      }
  }
  ```

### 78. Public Lock Exposure Detector
* **Severity**: `HIGH`
* **Description**: Detects classes that synchronize on `this` (or use `synchronized` instance methods) while the instance is publicly reachable, letting external callers acquire the same monitor. This enables deadlock against an external lock holder and violates the encapsulation invariant that only the class controls its own synchronization.
* **Buggy Code**:
  ```java
  public class Service {
      public synchronized void process() { // BUG: `this` is the lock and is public
          // ...
      }
  }
  // external code can also do: synchronized (serviceInstance) { ... }
  ```
* **Fixed Code**:
  ```java
  public class Service {
      private final Object lock = new Object(); // private, unreachable to callers

      public void process() {
          synchronized (lock) {
              // ...
          }
      }
  }
  ```

### 79. ForkJoinTask Blocking Detector
* **Severity**: `MEDIUM`
* **Description**: Detects blocking calls (`Thread.sleep`, `Object.wait`, `Future.get()`, blocking I/O) made from within a `ForkJoinTask` body. `ForkJoinPool` uses a bounded set of carrier threads, so a blocked task ties one up without doing useful work, starving every other submitted task and parallel stream.
* **Buggy Code**:
  ```java
  ForkJoinPool.commonPool().submit(() -> {
      Thread.sleep(500); // BUG: blocks a carrier thread, starves the shared pool
      return fetchResult();
  });
  ```
* **Fixed Code**:
  ```java
  ForkJoinPool.commonPool().submit(() ->
      ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
          public boolean block() throws InterruptedException {
              Thread.sleep(500);
              return true;
          }
          public boolean isReleasable() { return false; }
      })
  );
  ```

### 80. Optimistic Read Validation Detector
* **Severity**: `HIGH`
* **Description**: Detects `StampedLock` optimistic reads whose data is used without a matching `validate(stamp)` call, or where `validate()` fails but the stale data is used anyway. An optimistic read stamp is only valid if no write lock was acquired in between, so skipping validation silently introduces torn-snapshot data corruption.
* **Buggy Code**:
  ```java
  long stamp = lock.tryOptimisticRead();
  int localX = x; // reading shared field
  int localY = y;
  // BUG: no validate() call before using localX/localY
  process(localX, localY);
  ```
* **Fixed Code**:
  ```java
  long stamp = lock.tryOptimisticRead();
  int localX = x;
  int localY = y;
  if (!lock.validate(stamp)) {
      stamp = lock.readLock();
      try { localX = x; localY = y; } finally { lock.unlockRead(stamp); }
  }
  process(localX, localY);
  ```

### 81. CompletableFuture Common-Pool Blocking Detector
* **Severity**: `HIGH`
* **Description**: Detects blocking operations executed inside `CompletableFuture` stages submitted without an explicit `Executor` — i.e. running on the shared `ForkJoinPool.commonPool()`. Blocking there starves that pool for every other caller in the JVM, including parallel streams.
* **Buggy Code**:
  ```java
  CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> {
      return blockingHttpCall(); // BUG: blocks a common-pool thread
  });
  ```
* **Fixed Code**:
  ```java
  Executor ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
  CompletableFuture<String> cf = CompletableFuture.supplyAsync(
      () -> blockingHttpCall(), ioExecutor); // dedicated executor, common pool untouched
  ```

---

## Phase 11: Thread-Safety of Additional Types

### 82. Shared Matcher Detector
* **Severity**: `HIGH`
* **Description**: Detects a single `java.util.regex.Matcher` instance used concurrently by multiple threads. Unlike the thread-safe `Pattern`, `Matcher` carries per-match state (position, groups, last-append offset), so concurrent use produces incorrect matches or `StringIndexOutOfBoundsException`.
* **Buggy Code**:
  ```java
  private static final Matcher SHARED = EMAIL_PATTERN.matcher("");

  boolean isEmail(String s) {
      SHARED.reset(s);      // BUG: shared mutable Matcher across threads
      return SHARED.matches();
  }
  ```
* **Fixed Code**:
  ```java
  private static final Pattern EMAIL_PATTERN = Pattern.compile("...");

  boolean isEmail(String s) {
      return EMAIL_PATTERN.matcher(s).matches(); // fresh Matcher per call
  }
  ```

### 83. Shared DecimalFormat Detector
* **Severity**: `HIGH`
* **Description**: Detects a single `DecimalFormat`/`NumberFormat` instance shared across threads without synchronization. Neither class is thread-safe; concurrent `format()`/`parse()` calls corrupt internal multiplier and grouping state, producing garbled output or `ParseException` — the numeric-formatting equivalent of `SimpleDateFormat` misuse.
* **Buggy Code**:
  ```java
  private static final DecimalFormat CURRENCY = new DecimalFormat("#,##0.00");

  String format(double amount) {
      return CURRENCY.format(amount); // BUG: shared mutable formatter
  }
  ```
* **Fixed Code**:
  ```java
  private static final ThreadLocal<DecimalFormat> CURRENCY =
      ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.00"));

  String format(double amount) {
      return CURRENCY.get().format(amount); // one instance per thread
  }
  ```

### 84. WeakReference Race Detector
* **Severity**: `HIGH`
* **Description**: Detects two failure modes around `WeakReference`/`SoftReference`: using `get()`'s result without a null check, and a referent observed non-null on one thread but null on another, indicating it was collected mid-test. Either pattern produces an intermittent `NullPointerException` driven by GC timing.
* **Buggy Code**:
  ```java
  WeakReference<Foo> ref = new WeakReference<>(foo);

  void use() {
      Foo val = ref.get();
      val.doSomething(); // BUG: no null check, GC may have collected the referent
  }
  ```
* **Fixed Code**:
  ```java
  WeakReference<Foo> ref = new WeakReference<>(foo);

  void use() {
      Foo val = ref.get();
      if (val != null) {
          val.doSomething(); // guarded against concurrent collection
      }
  }
  ```

### 85. Stateful Lambda Detector
* **Severity**: `HIGH`
* **Description**: Detects lambdas/`Runnable`/`Callable` instances that capture a mutable container (an array, an outer field, or an Atomic used via get+set) and are subsequently executed concurrently. The JVM's effectively-final rule only covers the captured *reference*, not a mutable container's contents, so shared execution introduces a data race.
* **Buggy Code**:
  ```java
  int[] counter = {0}; // mutable captured container
  Runnable task = () -> counter[0]++; // BUG: unsynchronized shared mutation

  executor.submit(task);
  executor.submit(task);
  ```
* **Fixed Code**:
  ```java
  AtomicInteger counter = new AtomicInteger();
  Runnable task = counter::incrementAndGet; // atomic, race-free

  executor.submit(task);
  executor.submit(task);
  ```

### 86. Shared MessageDigest Detector
* **Severity**: `HIGH`
* **Description**: Detects a single `java.security.MessageDigest` instance shared across threads. Its internal digest state (running hash buffer, byte count, padding) is mutated by every `update()`/`digest()` call, so unsynchronized concurrent access corrupts the resulting hash without throwing any exception (accesses holding the instance's own monitor count as guarded since 1.9.1; a guard on any other lock object is not observed and is still flagged).
* **Buggy Code**:
  ```java
  private static final MessageDigest SHA256 = MessageDigest.getInstance("SHA-256");

  byte[] hash(byte[] data) {
      SHA256.update(data);      // BUG: shared mutable digest state
      return SHA256.digest();
  }
  ```
* **Fixed Code**:
  ```java
  byte[] hash(byte[] data) throws NoSuchAlgorithmException {
      MessageDigest digest = MessageDigest.getInstance("SHA-256"); // fresh instance per call
      digest.update(data);
      return digest.digest();
  }
  ```

---

## Phase 12: Operational & Hygiene Concurrency Issues

### 87. Interrupt Swallowing Detector
* **Severity**: `HIGH`
* **Description**: Detects `catch (InterruptedException e)` blocks that neither restore the interrupt flag nor rethrow, permanently suppressing the cooperative-cancellation signal so executors and blocking operations upstream can no longer observe that the thread was interrupted.
* **Buggy Code**:
  ```java
  try {
      Thread.sleep(100);
  } catch (InterruptedException e) {
      log.warn("sleep interrupted"); // BUG: interrupt flag is swallowed
  }
  ```
* **Fixed Code**:
  ```java
  try {
      Thread.sleep(100);
  } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // restore the flag for upstream code
      log.warn("sleep interrupted");
  }
  ```

### 88. MDC Context Leak Detector
* **Severity**: `MEDIUM`
* **Description**: Detects SLF4J MDC (Mapped Diagnostic Context) entries not cleared at task end. When a thread pool reuses a thread, diagnostic context (request ID, user, trace ID) set by one task leaks into the next task run on that thread, making unrelated log lines look correlated.
* **Buggy Code**:
  ```java
  void handleRequest(String requestId) {
      MDC.put("requestId", requestId);
      process();
      // BUG: no cleanup — next task on this pooled thread inherits requestId
  }
  ```
* **Fixed Code**:
  ```java
  void handleRequest(String requestId) {
      MDC.put("requestId", requestId);
      try {
          process();
      } finally {
          MDC.clear(); // prevents leakage to the next pooled task
      }
  }
  ```

### 89. System Property Mutation Detector
* **Severity**: `MEDIUM`
* **Description**: Detects concurrent `System.setProperty()`/`clearProperty()` calls during an async test run. System properties are global mutable state backed by a single `Properties` instance, so concurrent writers race and pollute configuration read by unrelated threads or later tests.
* **Buggy Code**:
  ```java
  @Test
  void testA() {
      System.setProperty("myapp.timeout", "5000"); // BUG: races with other threads/tests
      runWithTimeout();
  }
  ```
* **Fixed Code**:
  ```java
  @Test
  void testA() {
      String original = System.getProperty("myapp.timeout");
      System.setProperty("myapp.timeout", "5000");
      try {
          runWithTimeout();
      } finally {
          if (original != null) System.setProperty("myapp.timeout", original);
          else System.clearProperty("myapp.timeout");
      }
  }
  ```

### 90. Future Ignored Detector
* **Severity**: `HIGH`
* **Description**: Detects `Future` instances returned from `ExecutorService.submit()` that are never inspected via `get()`/`isDone()`/`isCancelled()`/`cancel()`. When the submitted task throws, the exception is captured inside the `Future` and silently discarded if no one ever checks it.
* **Buggy Code**:
  ```java
  Future<?> f = executor.submit(this::processOrder);
  // BUG: result never checked — a thrown exception disappears silently
  ```
* **Fixed Code**:
  ```java
  Future<?> f = executor.submit(this::processOrder);
  try {
      f.get(); // surfaces any exception thrown by the task
  } catch (ExecutionException e) {
      log.error("processOrder failed", e.getCause());
  }
  ```

### 91. Explicit GC Detector
* **Severity**: `LOW`
* **Description**: Detects explicit `System.gc()`/`Runtime.gc()` invocations during a concurrent test run. Explicit GC causes a stop-the-world pause of indeterminate length, inflating latency measurements and skewing thread-scheduling timing enough to mask real concurrency bugs.
* **Buggy Code**:
  ```java
  void evictCache() {
      cache.clear();
      System.gc(); // BUG: forces a full STW pause, distorts timing under test
  }
  ```
* **Fixed Code**:
  ```java
  void evictCache() {
      cache.clear();
      // Let the JVM manage collection; do not force GC in production/test code
  }
  ```

### 92. Deprecated Thread API Detector
* **Severity**: `HIGH`
* **Description**: Detects use of the removed/deprecated `Thread.stop()`, `suspend()`, `resume()`, `destroy()`, and `countStackFrames()` methods. `stop()` releases all monitors held by the thread, leaving shared state partially updated, and `suspend()`/`resume()` are inherently deadlock-prone.
* **Buggy Code**:
  ```java
  Thread worker = new Thread(this::runTask);
  worker.start();
  // ...
  worker.stop(); // BUG: releases monitors mid-update, corrupting shared invariants
  ```
* **Fixed Code**:
  ```java
  private volatile boolean cancelled = false;
  Thread worker = new Thread(() -> {
      while (!cancelled) { runTaskStep(); }
  });
  worker.start();
  // ...
  cancelled = true; // cooperative cancellation instead of Thread.stop()
  worker.join();
  ```

### 93. Shared XML Parser Detector
* **Severity**: `HIGH`
* **Description**: Detects `DocumentBuilder`/`SAXParser`/`Transformer`/`XPath` instances shared across threads. Unlike their corresponding factories, these parser objects are not thread-safe, and concurrent parse/transform/evaluate calls corrupt results or throw `ConcurrentModificationException`.
* **Buggy Code**:
  ```java
  private static final DocumentBuilder BUILDER = factory.newDocumentBuilder();

  Document parse(InputStream in) throws Exception {
      return BUILDER.parse(in); // BUG: shared mutable DocumentBuilder
  }
  ```
* **Fixed Code**:
  ```java
  private static final DocumentBuilderFactory FACTORY = DocumentBuilderFactory.newInstance();

  Document parse(InputStream in) throws Exception {
      return FACTORY.newDocumentBuilder().parse(in); // fresh builder per call; factory is thread-safe
  }
  ```

### 94. Boxed Primitive Lock Detector
* **Severity**: `HIGH`
* **Description**: Detects `synchronized` blocks locking on cached boxed `Integer`/`Long` values, `Boolean.TRUE`/`FALSE`, or JEP 390 value-based classes (`Optional`, `Instant`, `LocalDate`, etc.). Because the JVM shares these instances by identity, synchronizing on them couples the monitor to unrelated code anywhere in the process.
* **Buggy Code**:
  ```java
  Integer accountId = 42; // within Integer cache range [-128,127]
  synchronized (accountId) { // BUG: shares a JVM-wide monitor with any other code using 42
      updateBalance();
  }
  ```
* **Fixed Code**:
  ```java
  private final Object accountLock = new Object(); // unique instance

  void updateBalance() {
      synchronized (accountLock) {
          // ...
      }
  }
  ```

### 95. Shared TimeZone Detector
* **Severity**: `HIGH`
* **Description**: Detects `java.util.TimeZone` instances whose mutable state (`setRawOffset()`, `setID()`) is modified while accessed from multiple threads. Concurrent writes, or a write racing a read, produce non-deterministic offsets and IDs — silently wrong date/time arithmetic that is notoriously hard to reproduce.
* **Buggy Code**:
  ```java
  TimeZone shared = TimeZone.getDefault();

  void adjustForRegion(int offsetMillis) {
      shared.setRawOffset(offsetMillis); // BUG: mutates a shared, possibly concurrently-read TimeZone
  }
  ```
* **Fixed Code**:
  ```java
  void adjustForRegion(int offsetMillis) {
      TimeZone copy = (TimeZone) TimeZone.getDefault().clone(); // per-call private copy
      copy.setRawOffset(offsetMillis);
      use(copy);
  }
  ```

### 96. Uncaught Exception Handler Detector
* **Severity**: `MEDIUM`
* **Description**: Detects threads started without a custom `Thread.UncaughtExceptionHandler` that subsequently throw an uncaught exception. Without a handler, the exception only reaches the thread group's default (stderr) handler, so the submitting code has no way to detect that the thread died.
* **Buggy Code**:
  ```java
  Thread worker = new Thread(this::riskyTask);
  worker.start(); // BUG: no UncaughtExceptionHandler — failures vanish to stderr
  ```
* **Fixed Code**:
  ```java
  Thread worker = new Thread(this::riskyTask);
  worker.setUncaughtExceptionHandler((t, ex) ->
      log.error("Worker thread {} died", t.getName(), ex)); // failure now observable
  worker.start();
  ```

---

## Phase 13: Additional Concurrency-Bug Categories (1.0.0+)

### 97. Daemon Thread Hygiene Detector
* **Severity**: `MEDIUM`
* **Description**: Flags `Thread` instances created by user code without `setDaemon(true)` that are still alive at detector tear-down. A leaked non-daemon thread keeps the JVM from exiting, and the resulting hang is usually blamed on whatever test happens to be running when CI times out rather than on the actual leaking test.
* **Buggy Code**:
  ```java
  void startWorker() {
      Thread worker = new Thread(() -> pollQueue());
      worker.start(); // non-daemon by default; blocks JVM exit if never joined/shut down
  }
  ```
* **Fixed Code**:
  ```java
  void startWorker() {
      Thread worker = new Thread(() -> pollQueue());
      worker.setDaemon(true); // JVM can exit even if this thread is still running
      worker.start();
  }
  ```

### 98. Notify Without Monitor Detector
* **Severity**: `HIGH`
* **Description**: Detects `notify()`/`notifyAll()` calls attempted while the calling thread does not hold the target object's monitor. The JVM throws `IllegalMonitorStateException` for this at runtime, but in production that exception is often swallowed by a high-level catch-all, leaving `wait()`-ers blocked forever in a way that looks like a deadlock rather than a missed signal.
* **Buggy Code**:
  ```java
  void publish(Object mutex) {
      mutex.notifyAll(); // IllegalMonitorStateException: lock not held
  }
  ```
* **Fixed Code**:
  ```java
  void publish(Object mutex) {
      synchronized (mutex) {
          mutex.notifyAll(); // legal: current thread holds the monitor
      }
  }
  ```

### 99. Shared SecureRandom Detector
* **Severity**: `HIGH`
* **Description**: Flags a `SecureRandom` instance accessed from more than one thread. Thread safety is provider-dependent — some providers serialize internally at a large contention cost, others (Bouncy Castle, custom SPIs) may not synchronize at all, producing biased, predictable, or duplicate output under concurrent access, which is a security bug.
* **Buggy Code**:
  ```java
  private final SecureRandom secureRandom = new SecureRandom();

  byte[] nextToken() {
      byte[] buf = new byte[16];
      secureRandom.nextBytes(buf); // shared across all worker threads
      return buf;
  }
  ```
* **Fixed Code**:
  ```java
  private static final ThreadLocal<SecureRandom> secureRandom =
      ThreadLocal.withInitial(SecureRandom::new);

  byte[] nextToken() {
      byte[] buf = new byte[16];
      secureRandom.get().nextBytes(buf); // each thread owns its instance
      return buf;
  }
  ```

### 100. Shared WeakHashMap Detector
* **Severity**: `HIGH`
* **Description**: Detects `WeakHashMap` or `IdentityHashMap` instances accessed from more than one thread. Both are documented as not thread-safe; `WeakHashMap`'s GC-driven cleanup mutates its table on every `get`/`put` without locking (risking infinite loops in the entry chain), and `IdentityHashMap`'s linear-probing open addressing can silently drop or duplicate entries under concurrent puts.
* **Buggy Code**:
  ```java
  private final Map<Key, Value> cache = new WeakHashMap<>();

  Value lookup(Key k) {
      return cache.computeIfAbsent(k, this::load); // accessed by many threads
  }
  ```
* **Fixed Code**:
  ```java
  private final Map<Key, Value> cache =
      Collections.synchronizedMap(new WeakHashMap<>());

  Value lookup(Key k) {
      synchronized (cache) {
          return cache.computeIfAbsent(k, this::load);
      }
  }
  ```

### 101. Shared JDBC Connection Detector
* **Severity**: `HIGH`
* **Description**: Detects `Connection`, `Statement`, `PreparedStatement`, or `ResultSet` instances accessed from more than one thread. The JDBC spec does not require any of these to be thread-safe, and production drivers document a single `Connection` as usable by at most one thread at a time — concurrent access can mix result-set cursors, corrupt the wire protocol, or leak transaction state between threads.
* **Buggy Code**:
  ```java
  private final Connection sharedConnection = dataSource.getConnection();

  void runQuery(String sql) throws SQLException {
      try (Statement st = sharedConnection.createStatement()) { // shared across threads
          st.executeQuery(sql);
      }
  }
  ```
* **Fixed Code**:
  ```java
  void runQuery(String sql) throws SQLException {
      try (Connection conn = dataSource.getConnection(); // per-thread checkout
           Statement st = conn.createStatement()) {
          st.executeQuery(sql);
      }
  }
  ```

---

## Phase 14: Additional Thread-Unsafe Primitives & Publication Hazards (1.7.0+)

### 102. Shared Stateful Crypto Detector
* **Severity**: `HIGH`
* **Description**: Detects `Cipher`, `Mac`, and `Signature` instances shared across threads. Unlike `MessageDigest`, these carry mutable per-operation state across an `init → update* → doFinal` sequence; interleaved calls from different threads mix plaintext/ciphertext blocks or fold bytes from both callers into one running digest, silently breaking confidentiality, integrity, or authenticity.
* **Buggy Code**:
  ```java
  private final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

  byte[] encrypt(byte[] data) throws Exception {
      cipher.init(Cipher.ENCRYPT_MODE, key); // shared instance racing init/update/doFinal
      return cipher.doFinal(data);
  }
  ```
* **Fixed Code**:
  ```java
  byte[] encrypt(byte[] data) throws Exception {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); // fresh per call
      cipher.init(Cipher.ENCRYPT_MODE, key);
      return cipher.doFinal(data);
  }
  ```

### 103. Shared Deflater/Inflater Detector
* **Severity**: `HIGH`
* **Description**: Detects `Deflater`/`Inflater` instances shared across threads. Both wrap a native zlib stream advanced by every call; concurrent use interleaves bytes from different logical streams, producing corrupt/undecompressable output or a native-layer crash if one thread calls `end()` while another is mid-stream.
* **Buggy Code**:
  ```java
  private final Deflater deflater = new Deflater();

  byte[] compress(byte[] data) {
      deflater.setInput(data); // same Deflater used by concurrent callers
      return drain(deflater);
  }
  ```
* **Fixed Code**:
  ```java
  byte[] compress(byte[] data) {
      Deflater deflater = new Deflater(); // one instance per call/thread
      try {
          deflater.setInput(data);
          return drain(deflater);
      } finally {
          deflater.end();
      }
  }
  ```

### 104. This-Escape Detector
* **Severity**: `HIGH`
* **Description**: Detects a constructor publishing `this` before construction finishes — starting a thread, registering a listener, or storing `this` into a shared collection mid-constructor. Because final-field visibility and non-default field values are only guaranteed once the constructor returns, another thread that observes the reference early can see a partially-constructed object.
* **Buggy Code**:
  ```java
  class Service {
      Service(EventBus bus) {
          bus.register(this);             // listener may fire before ctor returns
          new Thread(this::poll).start(); // thread runs against half-built state
      }
  }
  ```
* **Fixed Code**:
  ```java
  class Service {
      private Service() { }

      static Service start(EventBus bus) {
          Service s = new Service();      // fully constructed first
          bus.register(s);                // publish only after construction completes
          new Thread(s::poll).start();
          return s;
      }
  }
  ```

### 105. ThreadLocalRandom Misuse Detector
* **Severity**: `MEDIUM`
* **Description**: Detects a cached `ThreadLocalRandom.current()` reference used from a thread other than the one that obtained it. The whole point of the class is per-thread isolation with no shared state; caching and reusing the reference across threads reintroduces contention and (since it lacks `Random`'s synchronization) state corruption and biased output.
* **Buggy Code**:
  ```java
  private final Random rng = ThreadLocalRandom.current(); // captured once, cached

  int nextValue() {
      return rng.nextInt(100); // reused from other threads later
  }
  ```
* **Fixed Code**:
  ```java
  int nextValue() {
      return ThreadLocalRandom.current().nextInt(100); // fetched fresh, per call, per thread
  }
  ```

---

## Phase 15: Asynchronous Flow & Lock-Usage Hazards (1.7.0+)

### 106. CompletableFuture Obtrude Detector
* **Severity**: `HIGH`
* **Description**: Detects `CompletableFuture.obtrudeValue()`/`obtrudeException()` calls, which force-overwrite a future's outcome regardless of any in-flight or already-published completion, bypassing the normal completion pipeline and racing with downstream consumers.
* **Buggy Code**:
  ```java
  CompletableFuture<String> future = fetchAsync();
  future.obtrudeValue("fallback"); // forces the outcome even if already completed/consumed
  ```
* **Fixed Code**:
  ```java
  CompletableFuture<String> future = fetchAsync();
  future.complete("fallback"); // no-op if already completed; no race with pipeline consumers
  ```

### 107. Spurious Wakeup Detector
* **Severity**: `HIGH`
* **Description**: Detects `wait()`/`Condition.await()` calls made outside a condition-checking loop. A thread can wake up spuriously — without `notify()` ever being called — and proceed as if the awaited condition were satisfied when it was not.
* **Buggy Code**:
  ```java
  synchronized (lock) {
      if (!ready) {        // single if-check
          lock.wait();     // may return without 'ready' becoming true
      }
  }
  ```
* **Fixed Code**:
  ```java
  synchronized (lock) {
      while (!ready) {     // re-check condition after every wakeup
          lock.wait();
      }
  }
  ```

### 108. Lock Upgrade Deadlock Detector
* **Severity**: `HIGH`
* **Description**: Detects a thread attempting to acquire the write lock of a `ReentrantReadWriteLock` while it still holds that lock's read lock. `ReentrantReadWriteLock` does not support upgrading a read lock to a write lock on the same thread, so the attempt deadlocks permanently. This is the detector that reports that condition: `LockDowngradeDetector` observes it too, through its own recording API, and forwards what it records here when both are enabled, so a caller who instrumented either API gets exactly one finding under this name.
* **Buggy Code**:
  ```java
  rwLock.readLock().lock();
  try {
      if (needsWrite(data)) {
          rwLock.writeLock().lock(); // deadlocks: same thread already holds the read lock
          try { update(data); } finally { rwLock.writeLock().unlock(); }
      }
  } finally {
      rwLock.readLock().unlock();
  }
  ```
* **Fixed Code**:
  ```java
  rwLock.readLock().lock();
  boolean needsWrite = needsWrite(data);
  rwLock.readLock().unlock(); // release the read lock before requesting the write lock

  if (needsWrite) {
      rwLock.writeLock().lock();
      try { update(data); } finally { rwLock.writeLock().unlock(); }
  }
  ```

### 109. TryLock Misuse Detector
* **Severity**: `HIGH`
* **Description**: Detects `Lock.unlock()` called after `tryLock()` returned `false` (or without checking its result at all). Unlocking a lock the thread never acquired throws `IllegalMonitorStateException` or corrupts the lock's internal state.
* **Buggy Code**:
  ```java
  lock.tryLock();
  try {
      doWork(); // runs even if tryLock() returned false
  } finally {
      lock.unlock(); // throws IllegalMonitorStateException if never actually acquired
  }
  ```
* **Fixed Code**:
  ```java
  if (lock.tryLock()) {
      try {
          doWork();
      } finally {
          lock.unlock(); // only unlock when acquisition actually succeeded
      }
  }
  ```

### 110. CompletableFuture Blocking Callback Detector
* **Severity**: `HIGH`
* **Description**: Detects blocking calls (`get()`, `join()`, `sleep()`) executed inside a `CompletableFuture` callback pipeline. Blocking a callback stage can exhaust the common pool (or whatever executor drives it), starving other tasks and potentially deadlocking the pipeline.
* **Buggy Code**:
  ```java
  future.thenApply(result -> {
      return otherFuture.get(); // blocking call inside a callback stage
  });
  ```
* **Fixed Code**:
  ```java
  future.thenCompose(result -> otherFuture); // compose asynchronously, never block inside a stage
  ```

---

## Phase 17: Shared Stateful JDK Objects, I/O Position Races & Contention Advisories

### 111. Shared ByteBuffer Detector
* **Severity**: `HIGH`
* **Description**: Detects `Buffer`/`ByteBuffer` instances whose position-mutating operations (relative `get`/`put`, `flip()`, `rewind()`, `clear()`, `mark()`/`reset()`, single-arg `position()`/`limit()`) are performed from more than one thread. None of this cursor state is synchronized, so unsynchronized concurrent use corrupts it, producing `BufferUnderflowException`/`BufferOverflowException` or silently interleaved data. Absolute `get(int)`/`put(int, ...)` calls don't touch the cursor and are not flagged.
* **Buggy Code**:
  ```java
  ByteBuffer shared = ByteBuffer.allocate(1024);

  void readInto(ByteBuffer target) {
      shared.flip();      // mutates position/limit
      target.put(shared);  // races with another thread's flip()/get()
  }
  ```
* **Fixed Code**:
  ```java
  ByteBuffer shared = ByteBuffer.allocate(1024);

  void readInto(ByteBuffer target) {
      ByteBuffer view = shared.duplicate(); // independent position/limit, same backing storage
      view.flip();
      target.put(view);
  }
  ```

### 112. Shared CharsetEncoder/Decoder Detector
* **Severity**: `HIGH`
* **Description**: Detects `CharsetEncoder`/`CharsetDecoder` instances shared across threads. Both carry mutable internal coding state advanced by every `encode()`/`decode()` call and are documented as not thread-safe; concurrent use interleaves state transitions, garbling output or throwing `IllegalStateException`.
* **Buggy Code**:
  ```java
  private final CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();

  ByteBuffer encode(CharBuffer text) throws CharacterCodingException {
      return encoder.encode(text); // shared coder state races across threads
  }
  ```
* **Fixed Code**:
  ```java
  ByteBuffer encode(CharBuffer text) throws CharacterCodingException {
      CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder(); // fresh per call
      return encoder.encode(text);
  }
  ```

### 113. Shared Checksum Detector
* **Severity**: `HIGH`
* **Description**: Detects `Checksum` implementations (`CRC32`, `CRC32C`, `Adler32`) shared across threads. None are thread-safe; concurrent `update()`/`getValue()`/`reset()` calls interleave updates to the same accumulator, silently producing a wrong checksum with no exception or crash.
* **Buggy Code**:
  ```java
  private final Checksum crc = new CRC32();

  long checksumOf(byte[] chunk) {
      crc.update(chunk, 0, chunk.length); // same accumulator updated by many threads
      return crc.getValue();
  }
  ```
* **Fixed Code**:
  ```java
  private final ThreadLocal<Checksum> crc = ThreadLocal.withInitial(CRC32::new);

  long checksumOf(byte[] chunk) {
      Checksum c = crc.get();
      c.reset();
      c.update(chunk, 0, chunk.length);
      return c.getValue();
  }
  ```

### 114. FileChannel Position Race Detector
* **Severity**: `HIGH`
* **Description**: Detects a `FileChannel`/`SeekableByteChannel` whose implicit (shared) position is read or mutated from more than one thread via `read(buffer)`, `write(buffer)`, `position(long)`, `truncate`, or `transferFrom`. Interleaved seek-then-read/write pairs from different threads perform I/O at the wrong offset, corrupting or losing data. The positional overloads that take an explicit offset are unaffected and never flagged.
* **Buggy Code**:
  ```java
  void appendLine(FileChannel channel, ByteBuffer data) throws IOException {
      channel.position(channel.size()); // implicit shared cursor
      channel.write(data);              // races with concurrent writers on the same channel
  }
  ```
* **Fixed Code**:
  ```java
  void appendLine(FileChannel channel, ByteBuffer data, long offset) throws IOException {
      channel.write(data, offset); // positional write, never touches the shared cursor
  }
  ```

### 115. Shared Iterator Detector
* **Severity**: `HIGH`
* **Description**: Detects a single `Iterator`/`ListIterator`/`Spliterator` instance driven from more than one thread. An iterator carries mutable cursor state confined to the thread that obtained it, regardless of whether the backing collection is itself thread-safe; racing `hasNext()`/`next()`/`remove()` calls on the same instance skip or duplicate elements, throw a spurious `NoSuchElementException`, or corrupt the underlying structure.
* **Buggy Code**:
  ```java
  Iterator<String> it = sharedList.iterator();

  void consume() { // called concurrently from multiple worker threads
      while (it.hasNext()) {
          process(it.next()); // races on shared cursor state
      }
  }
  ```
* **Fixed Code**:
  ```java
  void consume() {
      Iterator<String> it = sharedList.iterator(); // each thread obtains its own
      while (it.hasNext()) {
          process(it.next());
      }
  }
  ```

### 116. High-Contention Atomic Detector
* **Severity**: `LOW`
* **Description**: An advisory (non-correctness) detector for hot compare-and-swap loops on a shared `AtomicLong`/`AtomicInteger`/`AtomicReference` that would perform better as a `LongAdder`/`LongAccumulator`. Under heavy contention, every writer spins against the same memory location and the CAS failure rate climbs, collapsing throughput; striped accumulators avoid this for pure counters/statistics.
* **Buggy Code**:
  ```java
  private final AtomicLong counter = new AtomicLong();

  void recordHit() { // called from hundreds of threads; high CAS failure rate
      counter.incrementAndGet();
  }
  ```
* **Fixed Code**:
  ```java
  private final LongAdder counter = new LongAdder(); // striped cells reduce CAS contention

  void recordHit() {
      counter.increment();
  }

  long total() { return counter.sum(); }
  ```

### 117. Shared JSON Mapper Reconfiguration Detector
* **Severity**: `HIGH`
* **Description**: Detects a serializer/mapper (Jackson `ObjectMapper`, a Gson built via `GsonBuilder`, or similar) being reconfigured (`configure`, `registerModule`, builder-style setters) after it has already been used concurrently. Read/write operations are typically safe once configured, but a configuration mutation racing with an in-flight (de)serialization can corrupt output intermittently or throw out of an internal cache. The correct "configure fully, then publish" pattern is never flagged.
* **Buggy Code**:
  ```java
  static final ObjectMapper MAPPER = new ObjectMapper();

  String serialize(Object o) throws IOException {
      MAPPER.registerModule(new JavaTimeModule()); // reconfiguring a mapper already in use
      return MAPPER.writeValueAsString(o);
  }
  ```
* **Fixed Code**:
  ```java
  static final ObjectMapper MAPPER = new ObjectMapper()
          .registerModule(new JavaTimeModule()); // configure fully before publishing/sharing

  String serialize(Object o) throws IOException {
      return MAPPER.writeValueAsString(o);
  }
  ```

---

## Phase 19: Executor / Future / Latch Coordination (1.7.0+)

Three detectors that shipped implemented and unit-tested but unwired until 1.7.0 — no
`DetectorType`, no config flag, no registry field, so a real `@AsyncTest` never constructed
them. Now part of `detectAll` like every other detector.

### 118. Latch Misuse Detector
* **Severity**: `HIGH`
* **Description**: A `CountDownLatch` that is awaited but never counted down to zero (the
  awaiting thread blocks forever), or counted down more times than its initial count (a
  no-op past zero, but a sign the coordination logic is wrong).
* **Usage**:
  ```java
  var d = AsyncTestContext.latchMisuseDetector();
  d.registerLatch(latch, "workers-done", 2);
  d.recordAwait(latch);
  d.recordCountDown(latch);      // only 1 of 2 → flagged
  ```

### 119. Executor Deadlock Detector
* **Severity**: `CRITICAL`
* **Description**: Self-deadlock in a bounded or single-thread executor: tasks already
  running on the pool wait on sibling tasks submitted to that same pool, so no thread is
  ever free to run the siblings.
* **Usage**:
  ```java
  var d = AsyncTestContext.executorDeadlockDetector();
  d.registerExecutor(pool, "single-thread", 1);
  d.recordTaskSubmitted(pool);
  d.recordTaskStarted(pool);
  d.recordWaitingOnSibling(pool);   // pool saturated + waiting → flagged
  ```

### 120. Future Blocking Detector
* **Severity**: `HIGH`
* **Description**: A task blocks on `Future.get()` (or similar) from inside the same
  bounded pool that owns the future, consuming a worker thread while it waits — thread
  starvation that degrades into deadlock as the pool saturates.
* **Usage**:
  ```java
  var d = AsyncTestContext.futureBlockingDetector();
  d.registerExecutor(pool, "worker-pool", 2);
  d.recordTaskStarted(pool);
  d.recordBlockingWait(pool);
  ```

---

## Catalog Index by Phase

| Phase | Category | Total Detectors | Example Detectors | Default Severity |
|---|---|---|---|---|
| **Phase 1** | Core Concurrency | 3 | Deadlock, Livelock, Visibility | `HIGH`/`CRITICAL` |
| **Phase 2** | Resource Monitors | 15 | ThreadPoolDeadlock, MemoryOrdering, SemaphoreLeak | `HIGH` |
| **Phase 3** | Lock Monitors | 12 | LockContention, StampedLock, TryLockMisuse | `MEDIUM`/`HIGH` |
| **Phase 4** | Virtual Thread / Loom | 5 | CarrierPinning, CarrierExhaustion, CpuBoundTask | `HIGH` |
| **Phase 5** | Future / Callback | 8 | CFExceptionLeak, CFCompletionLeak, FutureIgnored | `HIGH` |
| **Phase 6** | Shared Util Monitors | 10 | SharedRandom, SimpleDateFormat, CalendarSharing | `MEDIUM` |
| **Phase 7** | Threading Hygiene | 8 | DaemonThread, DeprecatedThreadApi, UncaughtException | `LOW`/`MEDIUM` |
| **Phase 8** | Lock-Free Primitives | 8 | ABAProblem, OptimisticRead, VolatileArray | `HIGH` |
| **Phase 9** | Barrier Coordination | 10 | CountDownLatch, CyclicBarrier, Phaser | `HIGH` |
| **Phase 10**| Collection Safety | 10 | SynchronizedCollection, CopyOnWrite, MutableKey | `HIGH` |
| **Phase 11**| System / Global | 10 | SystemPropertyMutation, ExplicitGC, MDCLeak | `LOW`/`MEDIUM` |
| **Phase 12**| Miscellaneous | 12 | Statefulness, StreamClosing, WeakReferenceRace | `LOW`/`MEDIUM` |

---

## JDK 25/26 Detectors (Phases 16 & 18 — wired into detectAll)

Six detectors target concurrency features introduced or finalized in **JDK 24–26**. All
are part of the `@AsyncTest` `detectAll` pipeline (each has a `DetectorType` constant and
an `AsyncTestContext` accessor) and can also be instantiated standalone: record events
from your test body, call `analyze()`, and assert on the report. Each is implemented
against `String` keys + `Thread` / plain `Object` instances (no preview-API imports), so
it compiles and runs on the Java 21 baseline while modeling APIs that only exist on
JDK 24/25/26.

### 121. StableValue Misuse Detector
* **Class**: `StableValueMisuseDetector` · **JDK feature**: `StableValue` (JEP 502, preview JDK 25 → 26)
* **Severity**: `CRITICAL` (read-before-set / reentrant) · `HIGH` (double-set) · `LOW` (contention)
* **Description**: `StableValue<T>` is a deferred-immutable holder set at most once, then
  constant-folded by the JVM — the modern replacement for double-checked-locking and
  holder-class lazy init. The detector flags accesses that break the at-most-once contract.
* **Buggy Code**:
  ```java
  static final StableValue<Config> CONFIG = StableValue.of();

  Config get() {
      return CONFIG.orElseThrow();   // BUG: read before any set → NoSuchElementException
  }
  void init() {
      CONFIG.setOrThrow(load());
      CONFIG.setOrThrow(reload());   // BUG: second set → IllegalStateException / lost update
  }
  ```
* **Fixed Code**:
  ```java
  static final StableValue<Config> CONFIG = StableValue.of();

  Config get() {
      return CONFIG.orElseSet(() -> load());  // lazy, at-most-once, thread-safe; pure supplier
  }
  ```
* **Detect**:
  ```java
  var d = new StableValueMisuseDetector();
  d.recordRead("CONFIG", Thread.currentThread());          // before any recordSet → flagged
  d.recordSet("CONFIG", Thread.currentThread());
  d.recordSet("CONFIG", Thread.currentThread());           // double set → flagged
  assertTrue(d.analyze().hasIssues());
  ```

### 122. StructuredTaskScope Misuse Detector
* **Class**: `StructuredTaskScopeMisuseDetector` · **JDK feature**: `StructuredTaskScope` (JEP 505, preview JDK 25 → final JDK 26)
* **Severity**: `CRITICAL` (lifecycle violations) · `HIGH` (close-without-join)
* **Description**: The JDK 25 API (`StructuredTaskScope.open(Joiner)`) enforces a strict
  `open → fork* → join → get* → close` lifecycle. The detector models each scope as a small
  state machine and flags the transitions the runtime rejects.
* **Buggy Code**:
  ```java
  try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessfulOrThrow())) {
      Subtask<String> a = scope.fork(() -> fetchA());
      String early = a.get();        // BUG: read before join() → IllegalStateException
      scope.join();
      scope.fork(() -> fetchB());    // BUG: fork after join() → IllegalStateException
  }   // (also: closing without join() would cancel running subtasks)
  ```
* **Fixed Code**:
  ```java
  try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessfulOrThrow())) {
      Subtask<String> a = scope.fork(() -> fetchA());
      Subtask<String> b = scope.fork(() -> fetchB());
      scope.join();                              // wait first
      return combine(a.get(), b.get());          // then read
  }
  ```
* **Detect**:
  ```java
  var d = new StructuredTaskScopeMisuseDetector();
  Thread owner = Thread.currentThread();
  d.recordScopeOpened("s", owner);
  d.recordFork("s", "a", owner);
  d.recordJoin("s", owner);
  d.recordFork("s", "late", owner);              // fork after join → flagged
  assertTrue(d.analyze().hasIssues());
  ```

### 123. Gatherer Concurrency Misuse Detector
* **Class**: `GathererConcurrencyMisuseDetector` · **JDK feature**: Stream Gatherers (JEP 485, final JDK 24)
* **Severity**: `HIGH` (missing combiner) · `MEDIUM` (concurrent integrator)
* **Description**: On a parallel stream the runtime splits the input, runs the integrator on
  independent per-thread state, then merges with the **combiner**. A stateful gatherer with
  no combiner (or one whose integrator touches shared state) silently loses or corrupts
  results. The detector confirms multi-thread execution and judges whether it was safe.
* **Buggy Code**:
  ```java
  // Stateful integrator, NO combiner, used on a parallel stream → states can't merge
  Gatherer<T,?,R> g = Gatherer.ofSequential(initializer, integrator, finisher);
  list.parallelStream().gather(g).toList();   // forced sequential, or silently wrong if hand-rolled
  ```
* **Fixed Code**:
  ```java
  // Provide a combiner so per-thread states merge safely under parallelism:
  Gatherer<T,?,R> g = Gatherer.of(initializer, integrator, combiner, finisher);
  list.parallelStream().gather(g).toList();
  ```
* **Detect**:
  ```java
  var d = new GathererConcurrencyMisuseDetector();
  d.registerGatherer("running", /*hasCombiner*/ false, /*parallel*/ true);
  // integrator calls d.recordIntegrate("running", Thread.currentThread()) per element
  assertTrue(d.analyze().hasIssues());   // fires once seen on >1 thread without a combiner
  ```

### 124. LazyConstant Misuse Detector (Phase 18, 1.7.0+)
* **Class**: `LazyConstantMisuseDetector` · **JDK feature**: `LazyConstant` (Lazy Constants, second preview JDK 26 — renamed, simplified successor of `StableValue`)
* **Severity**: `CRITICAL` (reentrant supplier) · `HIGH` (null value / repeat computation / non-determinism) · `LOW` (compute convoy)
* **Description**: `LazyConstant.of(supplier)` computes at most once on first `get()`,
  caches the result, and lets the JVM constant-fold it. The `StableValue` low-level
  methods (`trySet`/`setOrThrow`/`orElseSet`) were removed; lazy collections moved to
  `List.ofLazy`/`Map.ofLazy`; null values throw `NullPointerException`. The classic
  mistakes migrate into the supplier — this detector flags them.
* **Buggy Code**:
  ```java
  static final LazyConstant<Config> CONFIG =
          LazyConstant.of(() -> maybeNullConfig());     // BUG: null → NPE on first get()

  static final LazyConstant<Config> SELF =
          LazyConstant.of(() -> SELF.get().refresh());  // BUG: reentrant → IllegalStateException
  ```
* **Fixed Code**:
  ```java
  static final LazyConstant<Config> CONFIG =
          LazyConstant.of(() -> loadConfig());   // pure, non-null, deterministic, fast

  Config c = CONFIG.get();                       // computes once, cached forever
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.lazyConstantMisuseDetector();   // or new LazyConstantMisuseDetector()
  d.recordComputeStart("CONFIG", Thread.currentThread());
  d.recordComputeEnd("CONFIG", Thread.currentThread(), null);   // null result → flagged
  assertTrue(d.analyze().hasIssues());
  ```

### 125. Final Field Mutation Detector (Phase 18, 1.7.0+)
* **Class**: `FinalFieldMutationDetector` · **JDK feature**: JEP 500 — Warnings About Uses of Deep Reflection to Mutate Final Fields (JDK 26)
* **Severity**: `HIGH` (any reflective final-field write) · `CRITICAL` (racing readers / concurrent mutators)
* **Description**: JDK 26 warns on `Field.set(...)` of `final` fields
  (`--illegal-final-field-mutation=warn`); a future release denies it. Independently of the
  deprecation, a post-construction write to a `final` field voids the JMM final-field
  publication guarantee: readers have no happens-before edge and may see the stale value
  forever (`final` reads can be constant-folded by the JIT).
* **Buggy Code**:
  ```java
  Field f = Config.class.getDeclaredField("maxRetries");
  f.setAccessible(true);
  f.setInt(config, 5);          // BUG: warn on JDK 26 → deny later; JMM violation today
  ```
* **Fixed Code**:
  ```java
  // Non-final (volatile if it must change), or constructor injection:
  var config = new Config(5);
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.finalFieldMutationDetector();   // or new FinalFieldMutationDetector()
  d.recordMutation("Config.maxRetries", Thread.currentThread());   // flagged (HIGH)
  d.recordRead("Config.maxRetries", otherThread);                  // escalates (CRITICAL)
  assertTrue(d.analyze().hasIssues());
  ```

### 126. Shared KDF Detector (Phase 18, 1.7.0+)
* **Class**: `SharedKdfDetector` · **JDK feature**: `javax.crypto.KDF` (JEP 510 — Key Derivation Function API, final JDK 25)
* **Severity**: `HIGH`
* **Description**: The `KDF` javadoc documents the type as **not thread-safe** unless the
  provider says otherwise. Concurrent `deriveKey()`/`deriveData()` calls on one shared
  instance can interleave provider state and silently derive wrong keys — no exception,
  just a key that fails to match the peer's. Sibling of `SHARED_MESSAGE_DIGEST`,
  `SHARED_SECURE_RANDOM`, and `SHARED_STATEFUL_CRYPTO`.
* **Buggy Code**:
  ```java
  private final KDF hkdf = KDF.getInstance("HKDF-SHA256");   // BUG: one instance...

  byte[] sessionKey(HKDFParameterSpec params) throws Exception {
      return hkdf.deriveData(params);                         // ...hit by every request thread
  }
  ```
* **Fixed Code**:
  ```java
  byte[] sessionKey(HKDFParameterSpec params) throws Exception {
      KDF hkdf = KDF.getInstance("HKDF-SHA256");   // per call (cheap) — or ThreadLocal<KDF>
      return hkdf.deriveData(params);
  }
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.sharedKdfDetector();   // or new SharedKdfDetector()
  d.recordAccess(kdf, "HKDF-SHA256", "deriveKey", threadA);
  d.recordAccess(kdf, "HKDF-SHA256", "deriveKey", threadB);   // 2 threads → flagged
  assertTrue(d.analyze().hasIssues());
  ```

### JDK 26 additions to existing detectors (1.7.0+)

* **`StructuredTaskScopeMisuseDetector`** — the JDK 26 sixth preview (JEP 525) adds
  `Joiner.onTimeout()`. New events: `recordJoinTimeout(scopeId, thread)` (join hit its
  deadline; subtasks cancelled) and `recordTimeoutSwallowed(scopeId, thread)` (a custom
  `onTimeout()` returned a fallback). New findings: **`Subtask.get()` after a join
  timeout** (`CRITICAL` — the subtask is not in `SUCCESS` state, `get()` throws) and
  **timeout-swallowing fallback with cancelled subtasks** (`LOW` warning — side effects
  may be half-applied).
* **`VirtualThreadPinningDetector`** — now JDK-version-aware. Events are classified as
  `MONITOR` (no longer pins on JDK 24+, JEP 491), `CLASS_INIT` (no longer pins on
  JDK 26+), `NATIVE` (always pins), or `OTHER`. A report whose events are *all* obsolete on
  the running JDK no longer surfaces as a finding, because there is nothing the user could
  act on; when at least one event still pins, the obsolete ones stay in the report text,
  annotated. `PinningReport.hasIssues()` is that predicate and is what the report path and
  the SPI pipeline both bind; `hasEffectivePinningIssues()` / `getObsoleteEventCount()`
  remain available for filtering by hand, and `hasPinningIssues()` still counts every
  recorded event regardless of JDK.

## Phase 19: Reactive Streams — Flow API (1.7.1+)

### 127. Flow Publisher Concurrency Detector
* **Class**: `FlowPublisherConcurrencyDetector` · **JDK feature**: `java.util.concurrent.Flow` (JDK 9+)
* **Severity**: `HIGH` (overlapping `onNext`, signals after a terminal signal), `MEDIUM` (delivery beyond recorded demand — conditional wording, since only recorded `request()` calls are visible)
* **Description**: The Flow API inherits the reactive-streams specification: signals to a
  `Subscriber` must be serialized (rule 1.3), at most one terminal signal may be delivered
  and nothing after it (rule 1.7), and a publisher must not outrun requested demand
  (rule 1.1). A hand-rolled `Publisher` that fans deliveries out to an executor breaks
  rule 1.3 first: two threads inside `onNext` at once corrupt any non-thread-safe
  subscriber state. Overlap is observed, not inferred — `recordNextStart`/`recordNextEnd`
  bracket each delivery and the finding is the high-water mark of concurrent in-flight
  deliveries. No demand finding is emitted if no `request()` was ever recorded.
* **Buggy Code**:
  ```java
  class FanOutPublisher implements Flow.Publisher<Event> {
      private final ExecutorService pool = Executors.newFixedThreadPool(4);
      public void publish(Event e) {
          for (Flow.Subscriber<? super Event> s : subscribers) {
              pool.submit(() -> s.onNext(e));   // rule 1.3 violation: unserialized onNext
          }
      }
  }
  ```
* **Fixed Code**:
  ```java
  // SubmissionPublisher serializes delivery per subscriber and honors demand
  try (SubmissionPublisher<Event> publisher = new SubmissionPublisher<>()) {
      publisher.subscribe(subscriber);
      publisher.submit(event);
  }
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.flowPublisherConcurrencyDetector();  // or new FlowPublisherConcurrencyDetector()
  d.recordNextStart(subscriber, threadA);
  d.recordNextStart(subscriber, threadB);   // 2 threads inside onNext at once → flagged
  d.recordNextEnd(subscriber);
  d.recordNextEnd(subscriber);
  assertTrue(d.analyze().hasIssues());
  ```

---

## Phase 20: FFM, VarHandle, Record & Class-Initialization Hazards (1.8.0+)

### 128. Confined Arena Thread Escape
* **Severity**: `CRITICAL` (JVM-confirmed) / `MEDIUM` (fallback)
* **Trust tier**: **verdict** when the JDK supplies `MemorySegment.isAccessibleBy`
* **Description**: Detects a `MemorySegment` allocated from `Arena.ofConfined()` (FFM API, final in JDK 22) being touched by a thread that does not own the arena, and access to a segment whose arena has already been closed. Confinement is a hard JVM rule rather than a synchronization question: the detector asks the JVM directly instead of inferring from the observed thread set, so a finding is a defect no lock can fix.
* **Buggy Code**:
  ```java
  MemorySegment shared;
  try (Arena arena = Arena.ofConfined()) {
      shared = arena.allocate(1024);          // bound to this thread
      executor.submit(() -> shared.get(JAVA_INT, 0));   // BUG: WrongThreadException
  }
  ```
* **Fixed Code**:
  ```java
  try (Arena arena = Arena.ofShared()) {      // or give each thread its own confined arena
      MemorySegment seg = arena.allocate(1024);
      executor.submit(() -> seg.get(JAVA_INT, 0));      // legal; now synchronize the accesses
  }
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.confinedArenaThreadEscapeDetector();
  d.recordArena(arena, "parseBuffer", Thread.currentThread());
  d.recordAllocation(seg, arena, "parseBuffer", 1024);
  d.recordAccess(seg, "parseBuffer", Thread.currentThread(), true);   // from the wrong thread → flagged
  assertTrue(d.analyze().hasIssues());
  ```

### 129. Shared Memory Segment Race
* **Severity**: `HIGH` (conflicting locks) / `MEDIUM` (no lock recorded) / `CRITICAL` (use after close)
* **Trust tier**: **verdict** when guards are recorded, **prompt** when they are not
* **Description**: Detects overlapping byte ranges of a shared `MemorySegment` touched concurrently by different threads with at least one write. `Arena.ofShared()` removes the confinement check but not the data race: plain segment `get`/`set` carries no memory-model guarantee. Pass a `guard` label naming the monitor held during an access and overlapping accesses that agree on it are treated as synchronized, which is what separates this detector's HIGH findings from a bare "two threads touched it".
* **Buggy Code**:
  ```java
  MemorySegment buf = arena.allocate(4096);
  // two threads, same bytes, no ordering
  buf.set(JAVA_INT, 0, compute());
  int seen = buf.get(JAVA_INT, 0);
  ```
* **Fixed Code**:
  ```java
  VarHandle INT = JAVA_INT.varHandle();       // atomic access mode
  INT.compareAndSet(buf, 0L, expected, next);
  // or partition: each thread owns buf.asSlice(offset, len)
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.sharedMemorySegmentRaceDetector();
  d.recordAccess(buf, "ringBuffer", 0, 8, true,  threadA);            // unguarded write
  d.recordAccess(buf, "ringBuffer", 4, 8, false, threadB);            // overlapping read → flagged
  d.recordAccess(buf, "ringBuffer", 0, 8, true,  threadA, "bufLock"); // guarded on both sides → silent
  assertTrue(d.analyze().hasIssues());
  ```

### 130. VarHandle Non-Atomic Update
* **Severity**: `HIGH` (lost update) / `MEDIUM` (plain-mode sharing)
* **Trust tier**: **verdict** for the lost update, **prompt** for plain-mode sharing
* **Description**: The `VarHandle` counterpart of `ATOMIC_NON_ATOMIC_UPDATE`. Detects a `get` followed by a `set` where `compareAndExchange` was needed, and separately, plain-mode access to a location several threads share. The access mode never rescues the compound operation: `getVolatile` then `setVolatile` loses updates exactly as readily as the plain pair, because volatile buys ordering, not atomicity across two calls. The plain-mode rule catches the mistake unique to `VarHandle` — `vh.get(o)` has no ordering even when the field is declared `volatile`.
* **Buggy Code**:
  ```java
  int v = (int) COUNT.getVolatile(holder);
  COUNT.setVolatile(holder, v + 1);           // BUG: another thread's write is overwritten
  ```
* **Fixed Code**:
  ```java
  COUNT.getAndAdd(holder, 1);                 // indivisible
  // or a CAS loop:
  int old; do { old = (int) COUNT.getVolatile(holder); }
  while ((int) COUNT.compareAndExchange(holder, old, old + 1) != old);
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.varHandleNonAtomicUpdateDetector();
  d.recordGet(COUNT, holder, "count", Mode.VOLATILE, Thread.currentThread());
  d.recordSet(COUNT, holder, "count", Mode.VOLATILE, Thread.currentThread());   // → flagged
  assertTrue(d.analyze().hasIssues());
  ```

### 131. Record Mutable Component Leak
* **Severity**: `HIGH` (observed mutation) / `MEDIUM` (structural risk)
* **Trust tier**: **verdict** for the observed mutation, **prompt** for the structural risk
* **Description**: Detects records shared across threads whose components hold mutable state. A record is only shallowly immutable: the language freezes the reference, not the `ArrayList` behind it. The detector fingerprints every component on first sight and re-reads it at analysis time, so a component whose contents changed during the run is reported as a fact rather than an inference. Components holding `java.util.concurrent` types are deliberately not reported.
* **Buggy Code**:
  ```java
  record Order(String id, List<Item> items) { }
  List<Item> items = new ArrayList<>();
  Order order = new Order("o-1", items);      // caller keeps a live handle
  items.add(extra);                           // mutates what every reader sees
  ```
* **Fixed Code**:
  ```java
  record Order(String id, List<Item> items) {
      Order { items = List.copyOf(items); }   // copies AND freezes
  }
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.recordMutableComponentLeakDetector();
  d.recordShared(order, "order", threadA);
  d.recordShared(order, "order", threadB);
  assertTrue(d.analyze().hasIssues());
  ```

### 132. Static Init Deadlock
* **Severity**: `CRITICAL` (recorded cycle) / `HIGH` (live-thread sample)
* **Trust tier**: **verdict** for the recorded cycle, **corroborating** for the sample
* **Description**: Detects deadlocks between class initializers, where the lock each thread waits on is the JVM's per-class initialization lock. `ThreadMXBean.findDeadlockedThreads()` walks monitors and ownable synchronizers; a class init lock is neither, so the platform's own deadlock finder returns `null` while the JVM is fully wedged. That blind spot is why this detector exists separately from `DEADLOCKS`. With no instrumentation it still samples live threads for `<clinit>` frames.
* **Buggy Code**:
  ```java
  class Config   { static final Object A = Registry.defaults(); }   // thread 1 enters here
  class Registry { static final Object B = Config.A; }              // thread 2 enters here
  ```
* **Fixed Code**:
  ```java
  class Config {
      private static final class Holder { static final Object A = Registry.defaults(); }
      static Object a() { return Holder.A; }   // initialization on first use, no cycle on class load
  }
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.staticInitDeadlockDetector();
  d.recordInitStart(Config.class, threadA);
  d.recordInitStart(Registry.class, threadB);
  d.recordInitRequest(Registry.class, threadA);
  d.recordInitRequest(Config.class, threadB);    // cycle → flagged
  assertTrue(d.analyze().hasIssues());
  ```

### 133. Virtual Thread Pooling
* **Severity**: `HIGH`
* **Trust tier**: **verdict** for the pooled-executor finding — the factory probe distinguishes a virtual-thread factory from a platform one by construction, and a per-task or platform-pooled executor stays silent. The reuse finding is as good as its instrumentation contract: call `recordTaskExecution` once per task.
* **Description**: Detects virtual threads being pooled or reused across tasks — the central anti-pattern JEP 444 warns about. A `ThreadPoolExecutor` (including `ScheduledThreadPoolExecutor` and the `Executors.newFixedThreadPool` family) built over `Thread.ofVirtual().factory()` caps concurrency at the pool size and keeps every pooled worker and its `ThreadLocal`s alive indefinitely. Registering an executor probes its factory with one unstarted, discarded thread; separately, a virtual thread observed executing more than one recorded task is flagged as reuse.
* **Buggy Code**:
  ```java
  ExecutorService pool =
      Executors.newFixedThreadPool(8, Thread.ofVirtual().factory());   // pooled virtual threads
  ```
* **Fixed Code**:
  ```java
  try (ExecutorService perTask = Executors.newVirtualThreadPerTaskExecutor()) {
      // one fresh virtual thread per task; bound concurrency with a Semaphore, not a pool
  }
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.virtualThreadPoolingDetector();
  d.registerExecutor(pool, "request-pool");
  assertTrue(d.analyze().hasIssues());
  ```

### 134. Platform Thread-Per-Task
* **Severity**: `HIGH` (per-task executor on platform threads) / `MEDIUM` (churn advisory)
* **Trust tier**: **verdict** for the executor finding — the probe task reports the actual thread kind. The churn finding is an advisory threshold (16 platform-thread creations with at least half already terminated) and reads as a prompt.
* **Description**: Detects thread-per-task execution on platform threads — one OS thread per task, the workload virtual threads exist for. Each platform thread reserves an OS thread and ~1 MB of stack; the pattern survives a unit test and collapses under production load. Registering a `newThreadPerTaskExecutor` runs one no-op probe task on it (bounded 200 ms wait) to learn the thread kind; independently, recorded short-lived platform-thread creation above the threshold is reported as churn while long-lived pool workers stay silent.
* **Buggy Code**:
  ```java
  for (Request r : requests) {
      new Thread(() -> handle(r)).start();   // one OS thread per request
  }
  ```
* **Fixed Code**:
  ```java
  for (Request r : requests) {
      Thread.startVirtualThread(() -> handle(r));
  }
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.platformThreadPerTaskDetector();
  Thread worker = new Thread(task);
  d.recordThreadCreated(worker);
  worker.start();
  ```

### 135. Shared Splittable Random
* **Severity**: `HIGH`
* **Trust tier**: **split** — verdict for the `synchronized (generator)` idiom, which it recognises and stays silent for, prompt for any other guard, which it cannot see. Its report says so.
* **Description**: Detects `SplittableRandom` and JEP 356 `RandomGenerator` instances (`L64X128MixRandom`, `Xoshiro256PlusPlus`, …) accessed from more than one thread. These generators are documented not thread-safe: the state transition is a plain non-atomic read-modify-write, so concurrent `nextLong()` calls interleave it — duplicated values and broken statistical guarantees with no exception. `java.util.Random` subclasses are excluded: `Random` belongs to `SHARED_RANDOM`, `SecureRandom` to `SHARED_SECURE_RANDOM`, `ThreadLocalRandom` to `THREAD_LOCAL_RANDOM_MISUSE`.
* **Buggy Code**:
  ```java
  static final SplittableRandom RNG = new SplittableRandom();   // shared by worker threads
  long id() { return RNG.nextLong(); }
  ```
* **Fixed Code**:
  ```java
  SplittableRandom perThread = parent.split();   // each thread gets its own generator
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.sharedSplittableRandomDetector();
  d.registerGenerator(rng, "ids");
  long v = rng.nextLong();
  d.recordAccess(rng, "ids", "nextLong");
  ```

## Phase 22: CompletableFuture Publication & Lambda Capture Hazards (1.9.5+)

Four detectors whose findings rest on a value the detector observed rather than on the shape of
the code: a `complete()` that returned `false`, a stage that finished after a cancel, a
combinator read while constituents were outstanding, two threads reading the same pre-value with
no serial order of the updates left that could explain it.
Each stays silent on the correctly written twin — see [examples 139–142](../examples/README.md).

### 136. CompletableFuture Completion Race
* **Severity**: `HIGH`
* **Trust tier**: **fact** — reports only completion attempts observed to lose, so a future completed by one thread is silent.
* **Description**: Detects several threads racing to complete the same `CompletableFuture`. `complete()` and `completeExceptionally()` are first-writer-wins and return `false` for every later caller; that boolean is the only record that a result was discarded, and almost nothing reads it. The expensive case is a loser carrying an exception: the failure vanishes and the caller sees a success. Severity is `HIGH` when a losing attempt carried an exception or a value differing from the winner's, `MEDIUM` when every loser carried the same value. A lone recorded attempt that lost to a completion the detector never saw (an `orTimeout`, a raw `complete()` elsewhere) is reported too, as `HIGH` with the winner marked not observed: the value it carried is gone either way.
* **Buggy Code**:
  ```java
  CompletableFuture<String> quote = new CompletableFuture<>();
  for (String p : providers) pool.execute(() -> quote.complete(quoteFrom(p)));  // losers dropped
  ```
* **Fixed Code**:
  ```java
  var slots = providers.stream().map(p -> supplyAsync(() -> quoteFrom(p), pool)).toList();
  allOf(slots.toArray(CompletableFuture[]::new)).join();   // one future each, nothing discarded
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.cfCompletionRaceDetector();
  d.complete(quote, "quote", value);          // the detector reads complete()'s own return value
  ```

### 137. CompletableFuture Cancellation Propagation
* **Severity**: `HIGH`
* **Trust tier**: **fact** — the `HIGH` finding needs a stage *completion* recorded after a cancel on the same pipeline; a cooperative stage records no completion after it and is silent, whether the cancel landed before the body was dispatched or during it. A start after the cancel is counted in the message, never a finding on its own: `cancel()` dequeues nothing, so a body already submitted begins regardless.
* **Description**: Detects work that outlives the cancellation of the future in front of it. `cancel()` completes only the future it is called on: it does not reach the stage feeding it, cannot stop a supplier already running on a pool, and ignores `mayInterruptIfRunning` — the JDK documents that a `CompletableFuture` never interrupts anything. So the caller believes the export stopped and every row is still written. A second `MEDIUM` finding flags `cancel(true)` itself, since anything relying on that interrupt is relying on something that will not happen.
* **Buggy Code**:
  ```java
  var export = supplyAsync(() -> exporter.exportAll(50_000), pool);
  export.thenApply(this::render).cancel(true);   // stops nothing; all 50,000 rows still land
  ```
* **Fixed Code**:
  ```java
  var view = new CompletableFuture<String>();
  runAsync(() -> exporter.exportCooperatively(50_000, view::isCancelled), pool);
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.cfCancellationPropagationDetector();
  String pipeline = "report-" + Thread.currentThread().getName();   // one label per pipeline instance
  d.recordWorkStarted(pipeline, "export", Thread.currentThread());
  d.cancel(view, pipeline, "view", false);
  d.recordWorkCompleted(pipeline, "export", Thread.currentThread());   // only if it really finished
  ```

### 138. CompletableFuture Combinator Misuse
* **Severity**: `HIGH`
* **Trust tier**: **fact** — an unawaited combinator is reported only with constituents still outstanding, an early read only when fewer had completed than the arity given.
* **Description**: Detects code that moves past a combinator before the group has finished. `allOf` and `anyOf` wait for nothing — they return a new future, and that future is the only thing that knows when the group is done. Dropping it, or reading it with `getNow`/`isDone`, lets the caller proceed mid-write. A third finding covers `anyOf` losers: once one constituent wins, a failure in any of the others reaches no handler. Overlaps `COMPLETABLEFUTURE_CHAIN`'s unawaited-chain finding, which tracks individual futures rather than the combinator API.
* **Buggy Code**:
  ```java
  CompletableFuture.allOf(row, audit, index);   // called for a side effect it does not have
  return "order written";
  ```
* **Fixed Code**:
  ```java
  CompletableFuture.allOf(row, audit, index).thenApply(v -> "order written");
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.cfCombinatorMisuseDetector();
  d.recordCombinator(all, "orderWrites", "allOf", 3, Thread.currentThread());
  row.whenComplete((v, ex) -> d.recordConstituentCompleted(all, "row", ex != null, Thread.currentThread()));   // whenComplete, not thenRun: a failed part must still be recorded
  d.recordAwait(all, "join", Thread.currentThread());
  ```

### 139. Lambda Captured-State Lost Update
* **Severity**: `HIGH`
* **Trust tier**: **fact** — fires only where two threads were observed reading the same pre-value *and* the recorded updates admit no serial order at all; stays silent when every recorded update held one monitor.
* **Description**: Detects proven lost updates to a lambda's captured state. A lambda captures the container, not a copy, so the `int[] counter = {0}` workaround for effectively-final leaves the contents as shared as any field. Where `STATEFUL_LAMBDA` reports the shape — ran on several threads, mutated a capture — and therefore fires identically on a correctly locked counter, this one compares the values the threads observed, and needs two things: two threads read the same value before writing back, and the recorded updates cannot be laid end to end as one serial chain (a value read twice more than it was written back was read after it had already been replaced). The second condition is what keeps a value that merely came round again from being reported: a flag toggled under a `ReentrantLock`, or a wrapping counter on `updateAndGet`, shows the same pre-value on two threads, and a same pre-value alone is not proof. The count it reports is the minimum number of lost writes consistent with the recorded values ("lost at least N"), never a sum over collision groups, which would assume an order the detector never saw. `incrementAndGet()` gives each thread a distinct pre-value and is silent; so is a consistently held monitor, sampled with `Thread.holdsLock`. Inconsistent guarding, or two different monitors, is still reported, and the message says which.
* **Buggy Code**:
  ```java
  int[] hits = {0};
  Runnable onRequest = () -> hits[0] = hits[0] + 1;   // read, add, write - three steps
  ```
* **Fixed Code**:
  ```java
  AtomicInteger hits = new AtomicInteger();
  Runnable onRequest = hits::incrementAndGet;         // one operation, no window
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.lambdaLostUpdateDetector();
  int before = hits[0];
  hits[0] = before + 1;
  d.recordReadModifyWrite(task, "hits", before, before + 1, Thread.currentThread());
  ```

## Phase 23: Virtual-Thread Scale Hazards (1.9.5+)

The JEP 444 first-order hazards are covered by Phases 6 and 21 — pinning, pooling, CPU-bound
tasks, carrier exhaustion, context leaks, thread-per-task. These three are the second-order set:
failures that only appear once virtual threads make concurrency unbounded, and that the older
detectors cannot see because they were written when the thread count was the pool size.
`LOCK_CONTENTION`, `THREAD_LEAK` and `THREAD_STARVATION` contain no reference to
`Thread.isVirtual()` at all. Examples [143–145](../examples/README.md).

### 140. Virtual Thread Resource Saturation
* **Severity**: `HIGH`
* **Trust tier**: **fact** — peak *virtual* waiters versus declared capacity, both counts; a fan-out bounded by a semaphore of the resource's own size is silent, and so is a queue that platform threads made.
* **Description**: Detects an unbounded virtual-thread fan-out queueing on a bounded resource. A fixed pool of eight platform threads could never ask for a ninth connection, so the pool size was admission control that nobody wrote down; removing the pool removes it, while the connection pool, the rate limiter and the downstream service stay as bounded as they were. JEP 444's own advice is to limit the resource with a `Semaphore` rather than to pool the threads. The count compared against the capacity is the peak number of virtual threads waiting at once, so a platform-only workload, or a platform burst with a virtual thread passing through at some other moment, is out of scope: a bounded pool cannot produce this, and `THREAD_POOL_DEADLOCK` covers that ground. There is deliberately no "holders exceeded the capacity" finding: a caller returns the resource and then records having done so, and in that window the next caller can legitimately be granted it, so an observed count above the capacity is instrumentation skew as often as a real breach.
* **Buggy Code**:
  ```java
  var pool = Executors.newVirtualThreadPerTaskExecutor();
  for (Request r : requests) pool.submit(() -> { try (var c = ds.getConnection()) { handle(r, c); } });
  ```
* **Fixed Code**:
  ```java
  Semaphore admission = new Semaphore(ds.getMaximumPoolSize());   // bound the resource, not the threads
  pool.submit(() -> { admission.acquire(); try (var c = ds.getConnection()) { handle(r, c); }
                      finally { admission.release(); } });
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.vthreadResourceSaturationDetector();
  d.registerResource("connections", ds.getMaximumPoolSize());
  d.recordAcquireStart("connections", Thread.currentThread());       // inside the semaphore, before getConnection()
  d.recordAcquired("connections", Thread.currentThread());           // got one ...
  d.recordAcquireAbandoned("connections", Thread.currentThread());   // ... or gave up (timeout): out of the queue
  ```

### 141. Virtual Thread Monitor Serialization
* **Severity**: `HIGH`
* **Trust tier**: **fact** — peak number of *virtual* threads queued at once and the number of distinct virtual waiters, both counts; a critical section nobody queues on is silent, and so is a queue that platform threads made.
* **Description**: Detects a monitor serialising a large virtual-thread fan-out — the hazard JEP 491 left behind. Before JDK 24 a blocking `synchronized` pinned its virtual thread to a carrier and `VIRTUAL_THREAD_PINNING` reported it; that detector now correctly marks monitor events obsolete from JDK 24 on. The throughput limit did not go with the pinning: `synchronized` still admits one thread at a time, and with the pool gone nothing bounds how many arrive. It is easy to miss precisely because the fix landed, since a JDK 24 upgrade reads as "the pinning warnings went away". The report states which side of JDK 24 it is on and points at the pinning detector below it. The count compared against the threshold is the peak number of virtual threads queued at once, so a queue that platform threads made, with a virtual thread or two passing through at other moments, is `LOCK_CONTENTION`'s finding and not this one. `LOCK_CONTENTION` cannot make this call the other way — it has no notion of a virtual thread.
* **Buggy Code**:
  ```java
  synchronized (lock) { var v = cache.get(k); if (v == null) { v = load(k); cache.put(k, v); } return v; }
  ```
* **Fixed Code**:
  ```java
  return cache.computeIfAbsent(k, this::load);   // admits every thread; or shrink what is under the lock
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.vthreadMonitorSerializationDetector();
  d.recordMonitorEnter(lock, "sessionCache", Thread.currentThread());
  synchronized (lock) { d.recordMonitorAcquired(lock, Thread.currentThread()); ... }
  ```

### 142. ThreadLocal Cache Degradation
* **Severity**: `MEDIUM`
* **Trust tier**: **fact** — distinct instances counted by identity; a shared value, a pooled helper and platform-only usage are all silent.
* **Description**: Detects a `ThreadLocal` that was a cache under a pool and became an allocator under virtual threads. `ThreadLocal<SimpleDateFormat>` is the standard answer to a helper that is not thread-safe, and on a pool it is a good one: eight workers means eight formatters for the life of the process, bounded by the pool, which is why nobody counts them. A thread per task means an instance per task, retained for that thread's life. Nothing fails — the object is still confined to one thread — so the code reads exactly as it did when it was a cache. Distinct from `VIRTUAL_THREAD_CONTEXT_LEAKS`, which counts distinct ThreadLocal *keys* per thread; here there is one key and the question is how many *instances* it produced.
* **Buggy Code**:
  ```java
  static final ThreadLocal<SimpleDateFormat> FORMAT =
          ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));   // one per task now
  ```
* **Fixed Code**:
  ```java
  static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");   // immutable, shared
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.threadLocalCacheDegradationDetector();
  d.recordCachedValue("FORMAT", FORMAT.get(), Thread.currentThread());
  ```

---

## Phase 24: JDK 26 Structured Concurrency and Lazy Constants

JEP 525 (Structured Concurrency, sixth preview) and JEP 526 (Lazy Constants, second preview) both
moved work from the JDK into the application: a `Joiner` you write, a `Configuration` lambda you
return, a mapping function that runs per element. These four detectors cover the surfaces those
changes created. `STRUCTURED_TASK_SCOPE_MISUSE` still owns the scope's own fork/join/close
lifecycle; nothing here repeats it.

There is no detector for JEP 522 (G1 GC synchronization reduction). It changes how application
threads and GC workers coordinate over the card table, and exposes no API a test can record
against, so a detector for it would be a guess dressed as a measurement.

### 143. Scope Joiner Misuse
* **Severity**: `CRITICAL` / `HIGH` / `MEDIUM` by finding
* **Trust tier**: **fact** — every finding is a recorded count: scopes bound, threads overlapping in `onComplete`, calls seen off the owner thread.
* **Description**: Detects misuse of the `StructuredTaskScope.Joiner` contract. A joiner is called from two directions at once: `onComplete` runs on whichever subtask thread finished, concurrently with its peers, while `result()` and the JDK 26 `onTimeout()` run on the owner. A joiner accumulating into a plain `ArrayList` is a data race no amount of correct scope usage removes. JEP 525's `onTimeout()` makes it worse by design — returning a partial result is now the recommended pattern, so an accumulator that used to be discarded on timeout is now read while cancelled subtasks are still writing to it. Also flags a joiner reused across scopes (it carries the previous run's state), and forking after `onComplete` asked for the short-circuit.
* **Buggy Code**:
  ```java
  final class Collecting<T> implements Joiner<T, List<T>> {
      private final List<T> done = new ArrayList<>();          // written from subtask threads
      public boolean onComplete(Subtask<? extends T> st) { done.add(st.get()); return false; }
      public List<T> onTimeout() { return List.copyOf(done); } // read on the owner, mid-write
  }
  ```
* **Fixed Code**:
  ```java
  final class Collecting<T> implements Joiner<T, List<T>> {
      private final Queue<T> done = new ConcurrentLinkedQueue<>();
      public boolean onComplete(Subtask<? extends T> st) { done.add(st.get()); return false; }
      public List<T> onTimeout() { return List.copyOf(done); }
  }
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.scopeJoinerMisuseDetector();
  d.recordJoinerBound(joiner, "orders", scopeId, Thread.currentThread());
  d.recordOnCompleteEnter(joiner, Thread.currentThread());
  d.recordAccumulate(joiner, Thread.currentThread());
  d.recordOnCompleteExit(joiner, Thread.currentThread(), false);
  ```

### 144. Scope Configuration Misuse
* **Severity**: `CRITICAL` / `HIGH` / `MEDIUM` / `LOW` by finding
* **Trust tier**: **fact** — requested settings are compared against effective ones, and scope lifetimes are ordered by a sequence counter rather than the clock.
* **Description**: Detects misuse of the `UnaryOperator<Configuration>` lambda JEP 525 introduced in place of the scope constructors. `Configuration` is immutable and every `withX` returns a new instance, so a lambda that does not hand back the value it derived from its own parameter applies nothing — the scope silently has no deadline, and one hung subtask hangs the test forever. Also flags a non-positive timeout (the timeout path becomes the only path), a wide fan-out with no deadline at all, a scope whose every `join()` expired, one `ThreadFactory` configured on scopes that are alive at the same time, and duplicate `withName` values among live scopes.
* **Buggy Code**:
  ```java
  var base = StructuredTaskScope.Configuration.defaults();
  try (var scope = StructuredTaskScope.open(joiner,
          cfg -> { cfg.withTimeout(Duration.ofSeconds(3)); return base; })) {   // timeout dropped
  ```
* **Fixed Code**:
  ```java
  try (var scope = StructuredTaskScope.open(joiner,
          cfg -> cfg.withTimeout(Duration.ofSeconds(3)).withName("order-fetcher"))) {
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.scopeConfigurationMisuseDetector();
  d.recordScopeOpened(scopeId, "order-fetcher", 3000L, threadFactory, Thread.currentThread());
  d.recordEffectiveConfiguration(scopeId, effectiveName, effectiveTimeoutMillis);
  d.recordJoinOutcome(scopeId, timedOut);
  d.recordScopeClosed(scopeId);
  ```

### 145. Scope Result Escape
* **Severity**: `CRITICAL` / `HIGH` / `MEDIUM` by finding
* **Trust tier**: **fact** — reads are ordered against the scope's close by a sequence counter, and the reading thread is compared against the recorded owner.
* **Description**: Detects a scope's results outliving the scope. JDK 25's joiners returned a `Stream<Subtask<T>>`; a stream is lazy and single-use, so holding one past `close()` failed early and loudly. JDK 26 returns a `List`, which is the ergonomic win everyone wanted and also a handle that stores happily in a field. Structured concurrency's guarantee is that subtasks do not outlive their scope — a handle read after `close()`, or on a thread that never called `join()`, has no happens-before edge to the writes it points at. Also flags publishing the handle before `join()` returned, and mutating the unmodifiable result list. Distinct from `STRUCTURED_TASK_SCOPE_MISUSE`, which covers reading a result *too early*; this one covers too late, or on the wrong thread.
* **Buggy Code**:
  ```java
  List<Subtask<Order>> results;
  try (var scope = StructuredTaskScope.open(Joiner.<Order>allSuccessfulOrThrow())) {
      scope.fork(this::fetchA);
      results = scope.join();
  }
  return results.get(0).get();          // the scope is gone
  ```
* **Fixed Code**:
  ```java
  try (var scope = StructuredTaskScope.open(Joiner.<Order>allSuccessfulOrThrow())) {
      scope.fork(this::fetchA);
      return scope.join().get(0).get();  // read inside the structure
  }
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.scopeResultEscapeDetector();
  d.recordScopeOpened(scopeId, Thread.currentThread());
  d.recordJoinCompleted(scopeId);
  d.recordResultHandle(results, "orders", scopeId);
  d.recordScopeClosed(scopeId);
  d.recordHandleRead(results, Thread.currentThread());
  ```

### 146. Lazy Collection Misuse
* **Severity**: `CRITICAL` / `HIGH` / `LOW` by finding
* **Trust tier**: **fact** — computations, values and dependency edges are all recorded; the cycle finding is a walk over edges that were actually observed.
* **Description**: Detects misuse of `List.ofLazy(size, fn)` and `Map.ofLazy(keys, fn)`, the lazy collections JEP 526 added beside `LazyConstant`. Where `LAZY_CONSTANT_MISUSE` covers one holder with one supplier, a lazy collection is *n* independent at-most-once computations sharing one mapping function, each running on whichever thread asked for that element first. That makes possible a failure a single constant cannot have: a mapping function that reaches back into its own collection couples two elements, and if the dependency runs both ways, two threads each hold one element and wait for the other — a deadlock the JDK breaks with `IllegalStateException` when the cycle is on one thread, and does not break when it is spread across two. Also flags a mapping function that ran twice, disagreed with itself, or returned `null` (which JDK 26 rejects), plus warnings for nested computation and for many readers queueing on one slow element.
* **Buggy Code**:
  ```java
  static final List<Cell> GRID = List.ofLazy(64, i ->
          new Cell(i, GRID.get((i + 1) % 64).weight()));   // every element waits on the next
  ```
* **Fixed Code**:
  ```java
  static final int[] WEIGHTS = computeWeights(64);          // eager base layer, no coupling
  static final List<Cell> GRID = List.ofLazy(64, i -> new Cell(i, WEIGHTS[(i + 1) % 64]));
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.lazyCollectionMisuseDetector();
  d.recordGet("GRID", i, Thread.currentThread());
  d.recordComputeStart("GRID", i, Thread.currentThread());
  d.recordComputeEnd("GRID", i, Thread.currentThread(), value);
  ```
