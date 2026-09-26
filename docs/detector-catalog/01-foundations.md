# Phases 1 to 3: foundations

Part of the [Detector Catalog](../DETECTOR_CATALOG.md).

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
* **Description**: Reports a field whose recorded values diverged between threads within one round, which is what a stale read of a field published without a happens-before edge (missing `volatile` or memory barrier) looks like. It records values only, so a field that is meant to change, a correct `AtomicInteger` counter for one, looks the same; the finding is an observation, tier FACT since 1.12.3, not a verdict.
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

## Phase 3: Behavioral & Runtime Hygiene

Detectors that observe unsafe usages of JDK classes and concurrent collections.

### 6. ThreadLocal Leak Detector
* **Severity**: `MEDIUM`
* **Description**: Detects `ThreadLocal` variables set during execution but not cleaned up, causing memory leaks in recycled thread pools. Cleanup is judged per thread and per round, because a value lives in one thread's map: a `remove()` recorded on one thread does not clear another thread's value, and one recorded in an earlier round does not clear a later round's. The accumulation line counts only values a thread still holds, so a thread that removed every `ThreadLocal` it set is not reported for retaining them.
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
