# Phases 19 and 20: Flow API and FFM

Part of the [Detector Catalog](../DETECTOR_CATALOG.md).

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
* **Description**: Detects records shared across threads (two threads touching one record inside one invocation round) whose components hold mutable state. A record is only shallowly immutable: the language freezes the reference, not the `ArrayList` behind it. The detector fingerprints every component on first sight and re-reads it at analysis time, so a component whose contents changed during the run is reported as a fact rather than an inference. Components holding `java.util.concurrent` types are deliberately not reported.
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
