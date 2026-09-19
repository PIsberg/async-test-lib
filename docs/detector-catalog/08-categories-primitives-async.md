# Phases 13 to 15: categories, primitives and async flow

Part of the [Detector Catalog](../DETECTOR_CATALOG.md).

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
* **Description**: Detects a thread attempting to acquire the write lock of a `ReentrantReadWriteLock` while it still holds that lock's read lock. `ReentrantReadWriteLock` does not support upgrading a read lock to a write lock on the same thread, so the attempt deadlocks permanently. This is the detector that reports that condition: `LockDowngradeDetector` observes it too, through its own recording API, and forwards what it records here when both are enabled, so a caller who instrumented either API gets exactly one finding under this name. A thread that already holds the write lock may take the read lock and then the write lock again, which is a legal reentrant acquire and is not reported. When the recording thread really holds the lock, the lock itself decides (`isWriteLockedByCurrentThread`, `getReadHoldCount`); the recorded read holds, counted per thread, decide only for a body that records acquisitions without taking the lock. Record a blocking `writeLock().lock()` only: a `tryLock()` made while holding the read lock returns `false` at once and does not deadlock. Trust tier `VERDICT`, on a corpus pair that takes the real lock in both halves (#566).
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
