# Phases 5 and 6: common types and virtual threads

Part of the [Detector Catalog](../DETECTOR_CATALOG.md).

## Phase 5: Thread-Safety of Common Types

### 53. Calendar Sharing Detector
* **Severity**: `MEDIUM`
* **Description**: `Calendar` is not thread-safe; concurrent `get()`/`set()`/`add()`/`getTime()` calls on a shared instance can interleave and silently corrupt the represented date with no exception thrown. The detector tracks shared registrations and flags mutation-during-read contention. A round of `get()` calls alone still counts as sharing, because after a `set()` the next `get()` recomputes the fields into the instance, so concurrent `get()` calls race. A read lock held over every `get()` guards them, except for the first `get()` after a recorded `set()` or `add()`: that one writes the recomputed fields, so it needs the write lock, and gets under one read lock after a `set()` are reported.
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
* **Description**: `java.util.Timer` runs all scheduled tasks on a single thread, and an uncaught exception in any `TimerTask` kills that thread — silently cancelling every remaining scheduled task with no error reported. The detector flags a timer thread that died and a task starved behind another. A task exception recorded on the timer thread is reported as a thread death only when that thread actually died, so a task that catches its exception, records it and carries on is not reported; an exception recorded from any other thread cannot be checked and is taken at its word. Starvation is observed, not timed (#575): a task recorded with `recordTaskRun(timer, name, this, taskName)` from inside `run()` is reported when a recorded run of a different task on the timer thread held that thread for a whole clock tick at or after the task's `scheduledExecutionTime()`: the task fell due inside the run, or was already due when the timer picked another task due at the same instant (#614). There is no duration threshold, so a lone slow task, or a short one stretched by a GC pause, is silent unless another task fell due inside it, and two short co-due tasks are silent. A fixed-delay repetition reports the instant it was picked rather than when it fell due, so it records itself with `recordFixedDelayTaskRun(timer, name, this, periodMs, taskName)`, which counts the due time from the previous pick (#615); its first execution, and a fixed-delay task recorded with the plain form, are not judged. A periodic task overrunning its own period is not reported.
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
