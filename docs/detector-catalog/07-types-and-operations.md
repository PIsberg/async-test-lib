# Phases 11 and 12: more types and operational issues

Part of the [Detector Catalog](../DETECTOR_CATALOG.md).

## Phase 11: Thread-Safety of Additional Types

### 82. Shared Matcher Detector
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

### 83. Shared DecimalFormat Detector
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

### 84. WeakReference Race Detector
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

### 85. Stateful Lambda Detector
* **Severity**: `HIGH`
* **Description**: Detects lambdas/`Runnable`/`Callable` instances that capture a mutable container (an array, an outer field, or an Atomic used via get+set) and are subsequently executed concurrently. The JVM's effectively-final rule only covers the captured *reference*, not a mutable container's contents, so shared execution introduces a data race. Mutation of a captured object that is thread-safe by type (named through `recordCapturedMutation(lambda, name, state, thread)`: `java.util.concurrent` and its `atomic` package, or a `Collections.synchronizedXxx` wrapper) is not reported, nor is mutation that one lock covered every time: the captured object's own monitor, a lock declared with `AsyncTestContext.holdingLock(...)`, or one the agent wove.
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

### 86. Shared MessageDigest Detector
* **Severity**: `HIGH`
* **Description**: Detects a single `java.security.MessageDigest` instance shared across threads. Its internal digest state (running hash buffer, byte count, padding) is mutated by every `update()`/`digest()` call, so unsynchronized concurrent access corrupts the resulting hash without throwing any exception (accesses holding the instance's own monitor count as guarded since 1.9.1; a guard on any other lock object is not observed and is still flagged).
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

## Phase 12: Operational & Hygiene Concurrency Issues

### 87. Interrupt Swallowing Detector
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

### 88. MDC Context Leak Detector
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

### 89. System Property Mutation Detector
* **Severity**: `HIGH`
* **Description**: Detects concurrent `System.setProperty()`/`clearProperty()` calls during an async test run. System properties are global mutable state backed by a single `Properties` instance, so concurrent writers race and pollute configuration read by unrelated threads or later tests. Writers that all held one lock the detector can see (`synchronized (System.getProperties())`, a lock declared with `AsyncTestContext.holdingLock(...)`, or one the agent wove) took turns and are only a restore-hygiene warning, not a finding.
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

### 90. Future Ignored Detector
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

### 91. Explicit GC Detector
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

### 92. Deprecated Thread API Detector
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

### 93. Shared XML Parser Detector
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

### 94. Boxed Primitive Lock Detector
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

### 95. Shared TimeZone Detector
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

### 96. Uncaught Exception Handler Detector
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
