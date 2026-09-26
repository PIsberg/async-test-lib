# Phases 7 to 9: patterns, lifecycle and environment

Part of the [Detector Catalog](../DETECTOR_CATALOG.md).

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

## Phase 8: Lifecycle & Structural Correctness

### 67. Executor Shutdown Detector
* **Severity**: `MEDIUM`
* **Description**: Flags `ExecutorService` instances that have tasks submitted but are never shut down (thread leak), or that are shut down without a following `awaitTermination()` call (submitted tasks may be silently abandoned or still running at test end). The executor's own state at analysis outranks what was recorded about it: a pool that is shut down but not terminated is reported even when `awaitTermination()` was recorded, because a wait that timed out leaves the tasks running; a pool closed with try-with-resources (`close()`) is not reported as never shut down although nothing was recorded; and a missing await is not reported once the pool has terminated, since nothing was left in flight. Executors are tracked by identity.
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
* **Description**: Detects the nested-monitor-lockout anti-pattern — performing a blocking operation (`Object.wait()`, `Future.get()`, `Lock.lock()`) while holding a monitor on a different object — which can deadlock two threads in a way invisible to a thread dump, and otherwise degrades throughput by holding a coarse lock across a blocking call. A `wait()` on the one monitor held, the canonical `synchronized (m) { while (!ready) m.wait(); }`, is silent: `wait()` releases the monitor it is called on. Record waits with `recordWaitAttempted(m)`, which reports only when another monitor stays held; an operation string naming `wait(` is read the same way, counting the recorded monitors beyond one.
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
* **Description**: Detects `StampedLock` optimistic reads whose data is used without a matching `validate(stamp)` call. An optimistic read stamp is only valid if no write lock was acquired in between, so skipping validation silently introduces torn-snapshot data corruption. A `validate()` that returns false is not a finding: it is the idiom's cue to re-read under the read lock or retry. Using the optimistic values anyway is, and it is seen where the use is recorded with `recordValuesUsed(lock, stamp, thread)`, passing the stamp the used values were read under: the failed optimistic stamp is reported, the read-lock stamp of a re-read is not. A use is judged against the latest `validate()` of its stamp, so a stamp that validated and then failed a revalidation is reported when its values are used after the failure. A `validate()` covers only the reads before it: data read under a stamp after its successful validation, and not validated again, is reported. Locks are tracked by identity, so two locks whose identity hashes collide stay separate.
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
