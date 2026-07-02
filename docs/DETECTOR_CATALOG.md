# Detector Catalog

`async-test-lib` includes **121 detectors** organized across different phases. Below is a categorized catalog detailing the most critical concurrency bugs detected by the library, accompanied by "Buggy Code" vs. "Fixed Code" examples.

---

## Phase 1: Core (Always Enabled)

These detectors run automatically on every `@AsyncTest` without configuration.

### 1. Deadlock Detector
* **Severity**: `CRITICAL`
* **Description**: Detects circular dependencies between threads waiting on monitors or reentrant locks.
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

## JDK 25/26 Preview-Era Detectors (Phase 16 — wired into detectAll)

Three detectors target concurrency features introduced or finalized in **JDK 24–26**.
Unlike the catalog above, these are **not** part of the `@AsyncTest` `detectAll`
pipeline — a pipeline detector needs a `DetectorType` enum constant, and that enum is a
locked file. They ship in `se.deversity.asynctest.diagnostics` and are used **directly**:
instantiate, record events from your test body, call `analyze()`, and assert on the
report. Each is implemented against `String` keys + `Thread` (no preview-API imports), so
it compiles and runs on the Java 21 baseline while modeling APIs that only exist on
JDK 24/25/26.

### A. StableValue Misuse Detector
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

### B. StructuredTaskScope Misuse Detector
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

### C. Gatherer Concurrency Misuse Detector
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
