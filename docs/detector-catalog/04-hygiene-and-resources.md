# Phases 3 and 4: hygiene and resources

Part of the [Detector Catalog](../DETECTOR_CATALOG.md).

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
