# Phase 2: core and monitors

Part of the [Detector Catalog](../DETECTOR_CATALOG.md).

## Phase 2: Core

### 9. False Sharing Detector
* **Severity**: `MEDIUM`
* **Status**: **Experimental - findings off by default.** Cache-line effects are not observable from pure Java: the detector estimates offsets by summing nominal type sizes in declaration order, while the JVM reorders fields, compresses references, and honors `@Contended` padding, so the estimated offsets do not correspond to real memory layout. Its reports are therefore not evidence of false sharing. Enable with `-Dasync-test.experimental.false-sharing=true`; without the property, `analyze()` returns an empty report (recording is unaffected). Thread sets are compared within one invocation round, so fields touched by different threads in different rounds, which never overlapped, are not reported, and the high-contention access threshold counts only accesses made in rounds where more than one thread was on the field.
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
* **Description**: Tracks `wait()`/`notify()` per monitor and reports a waiter that acts on a wakeup no notify accounted for: a `wait()` that returned with no recorded `notify()`/`notifyAll()` that could have woken it, after which the same thread went on without waiting again. That is an `if` guard where a `while` loop belongs, and a spurious wakeup (or a timed wait running out) then proceeds on a condition nobody established. The re-check is observed as a second recorded wait from the same thread; a round boundary closes a return its round left without one. A `notifyAll` accounts for every wait open when it is recorded, a `notify` for one. The `wasNotified` flag only silences: `true` accounts for a return, `false` alone is not a finding (#590). A `notify()` that finds nobody waiting is described as context and never reported on its own, because setting a flag and then calling `notifyAll()` does exactly that; a wait that begins after such a notify and is never signalled is `MissedSignalDetector`'s finding. A timed wait whose caller gives up at its deadline records that branch with `recordGaveUp(monitor)`, which closes its unsignalled return without a finding (#607); only the give-up branch closes it, so a thread that proceeds after a timeout is still reported, and a deadline loop that does not record its give-up is reported too. Trust tier `PROMPT`.
* **Buggy Code**:
  ```java
  synchronized (lock) {
      if (!conditionMet) {
          lock.wait(); // a spurious return proceeds with conditionMet still false
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
* **Description**: Tracks object construction start/end and cross-thread field access to catch unsafe publication — objects shared with other threads before their constructor completes can expose partially initialized fields due to compiler/CPU reordering. Record `recordConstructionStart(this)` and `recordConstructionEnd(this)` from inside the constructor: the validator checks the stack, so a start recorded outside any constructor of the object's class is ignored (the object is already built), and a read by another thread before the end is recorded counts only while the constructor is still on the constructing thread's stack. An end recorded late, after the finished object was published through a volatile, a concurrent collection or a lock, or never recorded at all, therefore reports nothing. A start recorded on the constructing thread at the same stack depth or shallower closes its earlier construction, so a pooled thread building a second instance of the class is not read as still building the first; a read made before that second constructor records its start cannot be told apart and still counts against the first.
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
* **Severity**: `HIGH`
* **Description**: Detects the ABA problem in lock-free CAS-based code, where a value changes from A to B and back to A between a thread's read and its `compareAndSet`, causing the CAS to spuriously succeed and corrupt the data structure. The finding is that interleaving: the thread records the read its CAS expects (`recordRead(name, value)`), other threads record a change away from the value and a change back to it, and the first thread then records a successful `recordCASAttempt` expecting the value it read. A change back recorded after the CAS still counts, because recording is not atomic with the operation: it counts when nothing recorded after the read, neither a change nor a successful CAS, took the variable off the value the CAS wrote before the next round started, since a toggle that really followed the CAS would have needed that. A value that goes A to B to A with no such CAS, or one toggled by the CAS thread itself, is counted as a cycle in the report's context and is not a finding; a CAS with no read recorded in its own round draws no verdict. The verdict assumes every change is recorded, and a toggle that ran wholly before the read but is recorded after it is reported, since its records are those of a real ABA.
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
* **Description**: Logs reads and writes per memory location and thread to prompt a check for visibility violations: a read that returned a different value than the write another thread recorded just before it. The log is in record order, not memory order, so the finding says only that the two records disagree (the read may have run before the write, or not seen it) and asks for a happens-before edge; it is PROMPT-tier, not a verdict. A read whose value a later-recorded write produced is not reported, since that is the log lagging behind a write the read did see.
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
* **Description**: Measures per-lock read/write acquisition counts and wait times to detect writer starvation, where a steady stream of readers keeps a non-fair `ReadWriteLock`'s writer waiting indefinitely. Trust tier `ADVISORY`: the finding is decided by a read-to-write count ratio above 10, or a recorded writer wait above 100 ms, and never by the lock itself, so it is a performance note rather than a correctness claim, and a build gated on `minTrust = PROMPT` or higher does not fail on it (#569). A writer that never acquires the lock is never recorded, so starvation that complete is not reported.
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
* **Severity**: `LOW` (tier `ADVISORY`)
* **Description**: Notes a single `Random` instance used from more than one thread. `java.util.Random` is thread-safe, so this is a performance note and never a bug: the threads contend on its one atomic seed, and `ThreadLocalRandom` is faster.
* **Contended Code** (correct, but slower):
  ```java
  static final Random random = new Random();
  int roll() { return random.nextInt(6); } // all threads contend on one seed's CAS loop
  ```
* **Faster Code**:
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
* **Description**: Reports a stuck waiter: a thread the condition's lock shows parked in `await()` when the run is analysed while the predicate it waits for already holds. That is the only finding, and it needs the predicate registration, `registerCondition(lock, condition, ready, name)` (#666). Everything else the detector sees is a note in the report, not a finding, because it comes from the body's own records or from the lock alone and correct code produces it too: a thread parked on a condition registered with its lock but no predicate (an idle consumer parks the same way), a recorded await with no recorded exit on a condition registered without its lock, an await abandoned in an earlier round, and an await that returned as woken with no signal recorded while it waited (a signal site not recorded, a spurious wakeup, or a lost signal). The detector still pairs every recorded `Condition.await()` with the `signal()`/`signalAll()` calls recorded while it waited, per condition and per thread, so one signal does not account for two woken waiters in that note. An await that timed out is not a finding, since a bounded poll that is never signalled runs exactly that way, and neither is a signal made while nobody waits, which is how predicate-guarded code runs whenever the producer gets there first; the report shows the latter as a note (#583). Register the condition with the lock that created it, `registerCondition(lock, condition, name)` for a `ReentrantLock` or a `ReentrantReadWriteLock`'s write lock, and the waiters are read from the lock's wait queue at analysis: a thread parked on the condition is seen whether or not its await was recorded (a note without a predicate), and a recorded await with no thread parked behind it is a note. A lock held by another thread at analysis, or one that did not create the condition, is not read and reports nothing (#592). Pass the waiter's predicate too, `registerCondition(lock, condition, ready, name)`, and a thread parked while `ready` is false is an idle consumer, a note rather than a finding; a thread parked while it holds is a stuck waiter, and a predicate that throws leaves the parked threads unconfirmed with the exception in the note (#643). A thread parked while `ready` holds is a note too when other threads are queued to acquire the lock at analysis: a waiter `signal()` woke sits in that queue until it re-acquires the lock and consumes, and the waiters it did not wake stay parked meanwhile. Unrelated contention on the lock produces the same note, so a stuck waiter behind a contended lock is noted rather than reported (#657). A worker the runner interrupts at the round timeout has left the queue by then, so that case fails as the timeout rather than as this finding. An await a pooled worker recorded in one round and never exited is not merged into its await in the next round: it is noted as abandoned, and a `while` loop that awaits again before one recorded exit is still one wait (#593). Record signals under the condition's lock, and pass the timed await's result as `timedOut`.
* **Buggy Code**:
  ```java
  void put(String item) {
      lock.lock();
      try {
          buffer.addLast(item);
          notFull.signal(); // consumers wait on notEmpty: one already parked is never woken
      } finally { lock.unlock(); }
  }
  ```
* **Fixed Code**:
  ```java
  void put(String item) {
      lock.lock();
      try {
          buffer.addLast(item);
          notEmpty.signal(); // the condition this item's consumers are parked on
      } finally { lock.unlock(); }
  }
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
