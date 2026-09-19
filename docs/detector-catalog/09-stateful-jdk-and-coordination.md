# Phases 17 and 19: stateful JDK objects and coordination

Part of the [Detector Catalog](../DETECTOR_CATALOG.md).

## Phase 17: Shared Stateful JDK Objects, I/O Position Races & Contention Advisories

### 111. Shared ByteBuffer Detector
* **Severity**: `HIGH`
* **Description**: Detects `Buffer`/`ByteBuffer` instances whose position-mutating operations (relative `get`/`put`, `flip()`, `rewind()`, `clear()`, `mark()`/`reset()`, single-arg `position()`/`limit()`) are performed from more than one thread. None of this cursor state is synchronized, so unsynchronized concurrent use corrupts it, producing `BufferUnderflowException`/`BufferOverflowException` or silently interleaved data. Absolute `get(int)`/`put(int, ...)` calls don't touch the cursor and are not flagged.
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

### 112. Shared CharsetEncoder/Decoder Detector
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

### 113. Shared Checksum Detector
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

### 114. FileChannel Position Race Detector
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

### 115. Shared Iterator Detector
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

### 116. High-Contention Atomic Detector
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

### 117. Shared JSON Mapper Reconfiguration Detector
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

## Phase 19: Executor / Future / Latch Coordination (1.7.0+)

Three detectors that shipped implemented and unit-tested but unwired until 1.7.0 — no
`DetectorType`, no config flag, no registry field, so a real `@AsyncTest` never constructed
them. Now part of `detectAll` like every other detector.

### 118. Latch Misuse Detector
* **Severity**: `HIGH`
* **Description**: A `CountDownLatch` that is awaited but never counted down to zero (the
  awaiting thread blocks forever), or counted down more times than its initial count (a
  no-op past zero, but a sign the coordination logic is wrong).
* **Usage**:
  ```java
  var d = AsyncTestContext.latchMisuseDetector();
  d.registerLatch(latch, "workers-done", 2);
  d.recordAwait(latch);
  d.recordCountDown(latch);      // only 1 of 2 → flagged
  ```

### 119. Executor Deadlock Detector
* **Severity**: `CRITICAL`
* **Description**: Self-deadlock in a bounded or single-thread executor: tasks already
  running on the pool wait on sibling tasks submitted to that same pool, so no thread is
  ever free to run the siblings.
* **Usage**:
  ```java
  var d = AsyncTestContext.executorDeadlockDetector();
  d.registerExecutor(pool, "single-thread", 1);
  d.recordTaskSubmitted(pool);
  d.recordTaskStarted(pool);
  d.recordWaitingOnSibling(pool);   // pool saturated + waiting → flagged
  ```

### 120. Future Blocking Detector
* **Severity**: `HIGH`
* **Description**: A task blocks on `Future.get()` (or similar) from inside the same
  bounded pool that owns the future, consuming a worker thread while it waits — thread
  starvation that degrades into deadlock as the pool saturates.
* **Usage**:
  ```java
  var d = AsyncTestContext.futureBlockingDetector();
  d.registerExecutor(pool, "worker-pool", 2);
  d.recordTaskStarted(pool);
  d.recordBlockingWait(pool);
  ```
