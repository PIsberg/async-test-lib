# Detector Catalog

`async-test-lib` includes **124 detectors** organized across different phases. Below is a categorized catalog detailing the most critical concurrency bugs detected by the library, accompanied by "Buggy Code" vs. "Fixed Code" examples.

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

## Phase 1 (cont.): Core Concurrency

### 8. Livelock Detector
* **Severity**: `CRITICAL`
* **Description**: Detects livelock (threads repeatedly changing state in response to each other without making progress) and thread starvation, using thread state transitions and CPU time sampled via `ThreadMXBean` to distinguish stalled threads from genuine deadlocks.
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
* **Severity**: `HIGH`
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
* **Severity**: `HIGH`
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
* **Severity**: `HIGH`
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
* **Severity**: `HIGH`
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
* **Severity**: `HIGH`
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
* **Severity**: `HIGH`
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
* **Description**: Flags incorrect `ReentrantReadWriteLock` upgrade attempts — a thread holding a read lock that calls `writeLock().lock()` deadlocks immediately because the write lock cannot be granted while any read lock is held, including its own. The correct write-then-read downgrade pattern is not flagged.
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

### 72. Uncommitted Changes Detector
* **Severity**: `LOW`
* **Description**: Runs `git status --porcelain` to surface untracked or uncommitted files in the repository at test time, helping catch forgotten local edits that would make test behavior diverge from what CI sees on a clean checkout.
* **Buggy Code**:
  ```java
  @AsyncTest(threads = 4)
  void testFeature() {
      // Developer forgot to commit a config change before running the stress test;
      // CI checks out a clean tree and the test behaves differently.
  }
  ```
* **Fixed Code**:
  ```java
  // Before merging/publishing, verify a clean tree:
  UncommittedChangesReport report = new UncommittedChangesDetector().analyze();
  assertFalse(report.hasIssues(), "Uncommitted changes: " + report.uncommittedFiles);
  ```

---

## Phase 10: API Traps & Subtle Concurrency Bugs

### 73. ThreadLocal Contamination Detector
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

### 74. Atomic/Non-Atomic Update Mixing Detector
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

### 75. Synchronized Collection Iteration Detector
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

### 76. Shared Formatter Detector
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

### 77. ConcurrentMap Compute Recursion Detector
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

### 78. Synchronized-on-Literal Detector
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

### 79. Public Lock Exposure Detector
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

### 80. ForkJoinTask Blocking Detector
* **Severity**: `HIGH`
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

### 81. Optimistic Read Validation Detector
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

### 82. CompletableFuture Common-Pool Blocking Detector
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

### 83. Shared Matcher Detector
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

### 84. Shared DecimalFormat Detector
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

### 85. WeakReference Race Detector
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

### 86. Stateful Lambda Detector
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

### 87. Shared MessageDigest Detector
* **Severity**: `HIGH`
* **Description**: Detects a single `java.security.MessageDigest` instance shared across threads. Its internal digest state (running hash buffer, byte count, padding) is mutated by every `update()`/`digest()` call, so concurrent access silently corrupts the resulting hash without throwing any exception.
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

### 88. Interrupt Swallowing Detector
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

### 89. MDC Context Leak Detector
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

### 90. System Property Mutation Detector
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

### 91. Future Ignored Detector
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

### 92. Explicit GC Detector
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

### 93. Deprecated Thread API Detector
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

### 94. Shared XML Parser Detector
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

### 95. Boxed Primitive Lock Detector
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

### 96. Shared TimeZone Detector
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

### 97. Uncaught Exception Handler Detector
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

### 98. Daemon Thread Hygiene Detector
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

### 99. Notify Without Monitor Detector
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

### 100. Shared SecureRandom Detector
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

### 101. Shared WeakHashMap Detector
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

### 102. Shared JDBC Connection Detector
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

### 103. Shared Stateful Crypto Detector
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

### 104. Shared Deflater/Inflater Detector
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

### 105. This-Escape Detector
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

### 106. ThreadLocalRandom Misuse Detector
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

## Phase 15: Asynchronous Flow & Lock-Usage Hazards (1.8.0+)

### 107. CompletableFuture Obtrude Detector
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

### 108. Spurious Wakeup Detector
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

### 109. Lock Upgrade Deadlock Detector
* **Severity**: `HIGH`
* **Description**: Detects a thread attempting to acquire the write lock of a `ReentrantReadWriteLock` while it still holds that lock's read lock. `ReentrantReadWriteLock` does not support upgrading a read lock to a write lock on the same thread, so the attempt deadlocks permanently.
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

### 110. TryLock Misuse Detector
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

### 111. CompletableFuture Blocking Callback Detector
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

### 112. Shared ByteBuffer Detector
* **Severity**: `HIGH`
* **Description**: Detects `Buffer`/`ByteBuffer` instances whose position-mutating operations (relative `get`/`put`, `flip()`, `rewind()`, `clear()`, `mark()`/`reset()`, single-arg `position()`/`limit()`) are performed from more than one thread. None of this cursor state is synchronized, so concurrent use corrupts it, producing `BufferUnderflowException`/`BufferOverflowException` or silently interleaved data. Absolute `get(int)`/`put(int, ...)` calls don't touch the cursor and are not flagged.
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

### 113. Shared CharsetEncoder/Decoder Detector
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

### 114. Shared Checksum Detector
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

### 115. FileChannel Position Race Detector
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

### 116. Shared Iterator Detector
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

### 117. High-Contention Atomic Detector
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

### 118. Shared JSON Mapper Reconfiguration Detector
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

### D. LazyConstant Misuse Detector (Phase 18, 1.8.0+)
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

### E. Final Field Mutation Detector (Phase 18, 1.8.0+)
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

### F. Shared KDF Detector (Phase 18, 1.8.0+)
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

### JDK 26 additions to existing detectors (1.8.0+)

* **`StructuredTaskScopeMisuseDetector`** — the JDK 26 sixth preview (JEP 525) adds
  `Joiner.onTimeout()`. New events: `recordJoinTimeout(scopeId, thread)` (join hit its
  deadline; subtasks cancelled) and `recordTimeoutSwallowed(scopeId, thread)` (a custom
  `onTimeout()` returned a fallback). New findings: **`Subtask.get()` after a join
  timeout** (`CRITICAL` — the subtask is not in `SUCCESS` state, `get()` throws) and
  **timeout-swallowing fallback with cancelled subtasks** (`LOW` warning — side effects
  may be half-applied).
* **`VirtualThreadPinningDetector`** — now JDK-version-aware. Events are classified as
  `MONITOR` (no longer pins on JDK 24+, JEP 491), `CLASS_INIT` (no longer pins on
  JDK 26+), `NATIVE` (always pins), or `OTHER`. Obsolete events stay in the report but
  are annotated; use `PinningReport.hasEffectivePinningIssues()` /
  `getObsoleteEventCount()` to filter.
