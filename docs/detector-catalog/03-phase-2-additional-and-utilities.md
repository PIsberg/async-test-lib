# Phase 2: additional concurrency and utilities

Part of the [Detector Catalog](../DETECTOR_CATALOG.md).

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
* **Description**: Detects reuse of a broken CyclicBarrier: a party (the recording thread) coming back to a barrier it already saw broken, with no `reset()` in between, so its await throws `BrokenBarrierException` at once again, and keeps doing so for every caller until somebody resets it (#665). A party has seen the barrier broken when one of its arrivals or awaits found `isBroken()` true, or when it recorded a timeout or a break on it. One arrival that hits a break is not reported: a party cannot know the barrier is broken until its await throws, so a late party that catches `BrokenBarrierException` and drops a barrier broken to cancel its parties, with no reset, is correct. A `recordArrival` followed by the same thread's `recordAwait` is one arrival. A reset is recorded with `recordReset`, or observed when a later recorded arrival finds the barrier whole; either one closes what every party had seen. A barrier shared across rounds on fresh platform threads is never come back to by the same party, so that reuse is missed. On virtual threads (`useVirtualThreads`, where every body execution is a fresh thread) the party is the runner's worker slot, which does come back each round, so reuse that spans rounds is reported (#693). Neither a broken barrier nor a recorded `await()` timeout is reported by itself. Breaking one (a `reset()` with parties waiting, or interrupting them) is how its parties are cancelled, and a timed-out `await(timeout, unit)` breaks the barrier for every party; a caller that handles the `TimeoutException` with `reset()`, or backs off and drops the barrier, is correct. What fails is the party's next await on the still-broken barrier, so that is the finding, and a recorded timeout or break only tells the report what broke it. Reuse is decided by asking the barrier, so a recorded break on a barrier that is not broken at the await reports nothing, and a break nobody recorded is still seen. The check runs when the arrival or await is recorded, so a barrier that breaks between that check and the await is missed. It also reports a barrier left a party short (#631): when the runner times a round out, or when the run is analyzed, threads that recorded an arrival or await on the barrier are parked in an untimed `await()` on it, fewer of them than its parties, so they wait for a party that never comes. This is read from those threads' state and stack, never by asking the barrier, because `getNumberWaiting()` and `isBroken()` take the barrier's lock and a blocked barrier action holds it; probing it would hang the runner instead of failing the round. A timed `await(timeout, unit)` is not reported, since its timeout ends it; neither is a waiter that recorded nothing, nor a barrier whose recorded thread is inside `await()` without being parked in it, such as one running a blocked barrier action. A thread is attributed to the barrier it last recorded.
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
* **Description**: Detects a `ReentrantLock` still held when the run is analysed, by a thread other than the analysing one that has stopped working: a hold nobody gave back, whether the missing `unlock()` is an exception path with no `finally` or a helper that re-enters the lock and never releases the extra hold. Every later `lock()` parks for good. The evidence is the lock itself (`isLocked()`), not recorded counts, so a leak whose recorded acquire and release pair balances is still seen; when both this detector and `LockLeakDetector` are enabled and the forwarded counts already show the leak, it is left to that detector so one leak is one finding. The holder is named by the lock and looked up among the threads that recorded against it and the live platform threads: the hold is reported when no thread of that name is alive, or every one that is sits idle in a pool (`ThreadPoolExecutor.getTask`, `ForkJoinPool.awaitWork`), which is how a runner worker looks once its body ended; a holder alive anywhere else may still release the lock, so its hold is printed as context (#609). A body-started virtual thread that never recorded against the lock cannot be found and is treated as finished. Also reports starvation the lock corroborated: `recordStarvation(lock, thread, waitMs)`, called by the waiter once it has the lock, is a finding when another thread recorded acquiring the lock twice while the waiter stayed queued (`hasQueuedThread`), which is barging; a fair lock taken through `lock()` cannot produce that. A recorded wait with no barging seen, and the lock-less `recordStarvation(thread, waitMs)`, are context: a wait's length is not evidence (#608, #575). `tryLock()` timeouts recorded with `recordLockTimeout` are printed as context and are not a finding on their own: backing off on a timeout is correct, and a discarded `false` return is what `TRY_LOCK_MISUSE` observes (#589).
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
* **Description**: Flags the misconception that `volatile` on an array reference makes its elements volatile too — only reassignment of the reference is visible across threads, not writes to individual elements, so element updates can be invisible to other threads. An array whose every recorded element write and read held one lock the detector can see (`synchronized (array)`, a lock declared with `AsyncTestContext.holdingLock(...)`, or one the agent wove) is not reported: the lock supplies the ordering.
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
* **Description**: Flags synchronizing on a lock field that is not `final`, since a reassignment mid-flight lets different threads synchronize on different object instances, providing no real mutual exclusion. The finding needs the owning instance (`recordLockObject(lock, fieldId, ownerClass, owner)`): one instance synchronizing on more than one object is a reassigned lock. Recorded without the owner, the field's declaration decides what it can: a changing monitor on a `static` non-final field is a reassignment and is reported, and one on a `final` field can only be several instances and is not. On a non-final instance field it is also what several instances each holding their own lock look like, so it is listed in the report text as undecided, with the four-argument call that decides it, and not reported. For a non-final instance field the three-argument form is deprecated in favour of the four-argument one (#793), since without the instance it can never report.
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
* **Description**: Detects lost/missed signals: a `notify()`/`notifyAll()` that found no thread waiting, followed by a wait on the same condition that received no notify of its own, either ending unsignalled (a timed wait ran out) or still waiting when the run is analysed. A notify with nobody waiting is not reported on its own, because it is also the correct flag-then-notify handshake: a waiter that checks its predicate finds the flag set and never waits (#586). Each wait is matched to the wakeup its own thread records, so a stray wakeup cannot erase a live waiter. Record the monitor rather than a name where possible; named conditions share state with every monitor recorded under that name. The detector cannot see the predicate, so say whether the wait is guarded with `recordWait(monitor, guarded)` (#599): a guarded wait is never reported, since its loop re-tests the state a lost notify would have changed (a consumer polling after the last producer finished stays silent), and an unguarded wait is judged against every notify lost before it, so a notify consumed by another waiter no longer hides an earlier lost one. `recordWait(monitor)` and `recordWait(name)` do not say and keep the #586 rule, which reports the guarded poll and misses the hidden wait, unless the caller records every predicate evaluation with `recordPredicateCheck(monitor, satisfied)` (#635). An undeclared wait is then confirmed as a loop's only by the waiting thread's own next events on the condition in the same invocation round (#656): a check right after the wakeup that finds the predicate satisfied, or one that finds it unsatisfied followed by another wait; a wait reached through that back-edge is also confirmed when its last check is unsatisfied and no wait follows (a bounded poll giving up). A notify by the waiter, a wait with no check before it, or the end of the round closes the window, so `if (!ready) wait()` followed later by a check that finds `ready` still false is reported. Those calls alone cannot distinguish an `if (!ready) wait()` followed later by a check that finds `ready` true from a loop exit, or two consecutive `if (!ready) wait()` blocks from a loop that waited again. Marking the loops tells them apart (#669, since 1.12.2): call `recordLoopStart(monitor)` before `while (!ready)` and `recordLoopEnd(monitor)` after it, in a `finally`. Marks are per monitor: once any thread has marked a loop on a monitor, a wait on it recorded without a `guarded` flag is a loop's while its thread is inside a marked loop, and an `if`'s otherwise, judged like `recordWait(monitor, false)` whatever checks surround it, so both `if` shapes are reported after a lost notify and the marked loop stays silent. A monitor nobody marks, and a wait recorded before a monitor's first mark, keep the reading above; `recordWait(monitor, false)` still declares a single wait. A check never overrides an explicit `guarded` flag, and a wait that can no longer be confirmed is folded into a count, so memory holds at most one woken wait per thread per condition.
* **Buggy Code**:
  ```java
  // Thread A (runs first)
  synchronized (monitor) {
      monitor.notify(); // signal lost - no one is waiting yet
  }

  // Thread B (runs second)
  synchronized (monitor) {
      monitor.wait(); // no flag records the earlier notify: blocks forever
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
* **Description**: Detects lazy-init races where multiple threads observe a non-volatile field as `null` simultaneously and each proceeds to construct it, causing duplicate initialization and possible visibility inconsistency. Pass the instance that declares the field (`recordNullCheck(owner, fieldId, ...)`, `recordInitialization(owner, fieldId)`) so a holder created per invocation or per thread is judged on its own initialisations; keyed by the label alone, every such holder reads as one field initialised many times.
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

## Phase 2: Advanced Concurrency Utilities

### 39. Phaser Misuse Detector
* **Severity**: `HIGH`
* **Description**: Detects a `Phaser` whose party count came up short, decided on the real phaser. Two findings: a party that registers or arrives after every registered party has deregistered (the call returns a negative phase instead of synchronizing; pass it to `recordArrival(phaser, phase)`), and a stalled phase, where a registered party never arrived. A stall shows either as a timed wait whose phase is still current, with parties not arrived, when the run is analyzed, or as an `arriveAndAwaitAdvance()` bracketed by `recordAwaitAdvanceStarted(phaser)` and `recordAwaitAdvanceReturned(phaser, phase)` that never returned, whose phase is still current with parties not arrived and whose thread is still parked inside the phaser (#602). Registrations left behind with nobody waiting are not a stall. Termination itself is not a finding, since `arriveAndDeregister` to zero, `forceTermination` and `onAdvance` are how a phaser ends, and neither is a timeout whose phase advanced later (#587). Too many arrivals in one phase need no detector: the phaser throws `IllegalStateException`. Do not record `awaitAdvance(int)`'s result, which is negative in correct code that waits for termination.
* **Buggy Code**:
  ```java
  Phaser done = new Phaser(1);                    // one party, but every task leaves
  for (Runnable task : tasks) {
      executor.submit(() -> {
          task.run();
          done.arriveAndDeregister();             // the first task takes the count to zero;
      });                                         // every later call gets a negative phase
  }
  ```
* **Fixed Code**:
  ```java
  Phaser done = new Phaser(1);                    // the coordinator's own party
  for (Runnable task : tasks) {
      done.register();                            // count each task before it starts
      executor.submit(() -> {
          task.run();
          done.arriveAndDeregister();
      });
  }
  done.arriveAndDeregister();                     // terminates once every task has left
  ```

### 40. StampedLock Misuse Detector
* **Severity**: `HIGH`
* **Description**: Detects StampedLock misuse, matched per thread and per lock instance: an optimistic read whose stamp is never passed to `validate()`, a `validate()` that failed (or a zero stamp) followed by neither a read lock, a write lock nor a retried optimistic read, a read or write stamp recorded as acquired, never recorded as released, on a lock that `isWriteLocked()` or `getReadLockCount()` still reports held when the run is analysed, and a read stamp released again after its recorded holds came back, while another reader held the lock. `recordStampNotReleased` declarations are corroborated the same way rather than reported on trust. Pooled workers do not carry an open read or failed validation into the next round. Mode conversions are recorded with `recordConversion(lock, name, fromStamp, toStamp)`: the kind of each stamp is read from the stamp, a successful conversion from a lock stamp moves the outstanding acquisition to the stamp it returned, a conversion from an optimistic stamp counts as its validation, and the optimistic stamp a downgrade returns is not judged (#604). Of the wrong-stamp releases, only the repeated read release is reported: `unlockRead` checks only the stamp's version, so it silently takes another reader's hold, and it is counted only when the lock's reader count falls below the recorded read holds still outstanding. A mismatched mode or a repeated write release is refused by the lock itself with `IllegalMonitorStateException`, and releasing a stamp from another thread is legal (#604).
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
* **Severity**: `CRITICAL`
* **Description**: Detects an orphaned rendezvous: an exchange recorded as started that neither completed, timed out nor was interrupted by the time the run is analysed, which is a thread still inside `exchange()` waiting for a partner that is not coming. The typical cause is an odd number of callers. Starts and ends are matched per exchanger and per thread, so an end closes only a start the same thread recorded; an end on a thread with no open start closes nothing and is printed as context (#597). A recorded timeout or interrupt is how a thread left the exchange and is printed as context, not reported: a timed exchange that handles `TimeoutException` is the fix, and it used to be reported CRITICAL whether or not anything was left waiting (#585). A `null` payload is counted and printed too, but is not a finding: `exchange(null)` is permitted, and a payload-free handoff is how an Exchanger is used as a pure rendezvous where the meeting is the synchronisation (#521). Inside an `@AsyncTest`, an untimed orphan blocks its round until `timeoutMs`; the finding is printed with the timeout and named in its message. The runner marks the round timed out before it interrupts the workers, so a body that catches that interrupt and records it does not close the orphan (#598). An interrupt the body sends itself on a round that did not time out still ends the exchange.
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
  // A bounded wait, and a caller left without a partner gives up instead of blocking
  for (int i = 0; i < 3; i++) {
      executor.submit(() -> {
          try {
              return exchanger.exchange(myBuffer, 5, TimeUnit.SECONDS);
          } catch (TimeoutException noPartner) {
              return myBuffer; // keep our own buffer; nothing is left waiting
          }
      });
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
