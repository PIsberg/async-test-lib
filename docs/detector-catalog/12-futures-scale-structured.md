# Phases 22 to 24: futures, scale and structured concurrency

Part of the [Detector Catalog](../DETECTOR_CATALOG.md).

## Phase 22: CompletableFuture Publication & Lambda Capture Hazards (1.9.5+)

Four detectors whose findings rest on a value the detector observed rather than on the shape of
the code: a `complete()` that returned `false`, a stage that finished after a cancel, a
combinator read while constituents were outstanding, two threads reading the same pre-value with
no serial order of the updates left that could explain it.
Each stays silent on the correctly written twin — see [examples 139–142](../../examples/README.md).

### 136. CompletableFuture Completion Race
* **Severity**: `HIGH`
* **Trust tier**: **fact** — reports only completion attempts observed to lose, so a future completed by one thread is silent.
* **Description**: Detects several threads racing to complete the same `CompletableFuture`. `complete()` and `completeExceptionally()` are first-writer-wins and return `false` for every later caller; that boolean is the only record that a result was discarded, and almost nothing reads it. The expensive case is a loser carrying an exception: the failure vanishes and the caller sees a success. Severity is `HIGH` when a losing attempt carried an exception or a value differing from the winner's, `MEDIUM` when every loser carried the same value. A lone recorded attempt that lost to a completion the detector never saw (an `orTimeout`, a raw `complete()` elsewhere) is reported too, as `HIGH` with the winner marked not observed: the value it carried is gone either way.
* **Buggy Code**:
  ```java
  CompletableFuture<String> quote = new CompletableFuture<>();
  for (String p : providers) pool.execute(() -> quote.complete(quoteFrom(p)));  // losers dropped
  ```
* **Fixed Code**:
  ```java
  var slots = providers.stream().map(p -> supplyAsync(() -> quoteFrom(p), pool)).toList();
  allOf(slots.toArray(CompletableFuture[]::new)).join();   // one future each, nothing discarded
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.cfCompletionRaceDetector();
  d.complete(quote, "quote", value);          // the detector reads complete()'s own return value
  ```

### 137. CompletableFuture Cancellation Propagation
* **Severity**: `HIGH`
* **Trust tier**: **fact** — the `HIGH` finding needs a stage *completion* recorded after a cancel on the same pipeline; a cooperative stage records no completion after it and is silent, whether the cancel landed before the body was dispatched or during it. A start after the cancel is counted in the message, never a finding on its own: `cancel()` dequeues nothing, so a body already submitted begins regardless.
* **Description**: Detects work that outlives the cancellation of the future in front of it. `cancel()` completes only the future it is called on: it does not reach the stage feeding it, cannot stop a supplier already running on a pool, and ignores `mayInterruptIfRunning` — the JDK documents that a `CompletableFuture` never interrupts anything. So the caller believes the export stopped and every row is still written. A second `MEDIUM` finding flags `cancel(true)` itself, since anything relying on that interrupt is relying on something that will not happen.
* **Buggy Code**:
  ```java
  var export = supplyAsync(() -> exporter.exportAll(50_000), pool);
  export.thenApply(this::render).cancel(true);   // stops nothing; all 50,000 rows still land
  ```
* **Fixed Code**:
  ```java
  var view = new CompletableFuture<String>();
  runAsync(() -> exporter.exportCooperatively(50_000, view::isCancelled), pool);
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.cfCancellationPropagationDetector();
  String pipeline = "report-" + Thread.currentThread().getName();   // one label per pipeline instance
  d.recordWorkStarted(pipeline, "export", Thread.currentThread());
  d.cancel(view, pipeline, "view", false);
  d.recordWorkCompleted(pipeline, "export", Thread.currentThread());   // only if it really finished
  ```

### 138. CompletableFuture Combinator Misuse
* **Severity**: `HIGH`
* **Trust tier**: **fact** — an unawaited combinator is reported only with constituents still outstanding, an early read only when fewer had completed than the arity given.
* **Description**: Detects code that moves past a combinator before the group has finished. `allOf` and `anyOf` wait for nothing — they return a new future, and that future is the only thing that knows when the group is done. Dropping it, or reading it with `getNow`/`isDone`, lets the caller proceed mid-write. A third finding covers `anyOf` losers: once one constituent wins, a failure in any of the others reaches no handler. Overlaps `COMPLETABLEFUTURE_CHAIN`'s unawaited-chain finding, which tracks individual futures rather than the combinator API.
* **Buggy Code**:
  ```java
  CompletableFuture.allOf(row, audit, index);   // called for a side effect it does not have
  return "order written";
  ```
* **Fixed Code**:
  ```java
  CompletableFuture.allOf(row, audit, index).thenApply(v -> "order written");
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.cfCombinatorMisuseDetector();
  d.recordCombinator(all, "orderWrites", "allOf", 3, Thread.currentThread());
  row.whenComplete((v, ex) -> d.recordConstituentCompleted(all, "row", ex != null, Thread.currentThread()));   // whenComplete, not thenRun: a failed part must still be recorded
  d.recordAwait(all, "join", Thread.currentThread());
  ```

### 139. Lambda Captured-State Lost Update
* **Severity**: `HIGH`
* **Trust tier**: **fact** — fires only where two threads were observed reading the same pre-value *and* the recorded updates admit no serial order at all; stays silent when every recorded update held one monitor.
* **Description**: Detects proven lost updates to a lambda's captured state. A lambda captures the container, not a copy, so the `int[] counter = {0}` workaround for effectively-final leaves the contents as shared as any field. Where `STATEFUL_LAMBDA` reports the shape — ran on several threads, mutated a capture — and therefore fires identically on a correctly locked counter, this one compares the values the threads observed, and needs two things: two threads read the same value before writing back, and the recorded updates cannot be laid end to end as one serial chain (a value read twice more than it was written back was read after it had already been replaced). The second condition is what keeps a value that merely came round again from being reported: a flag toggled under a `ReentrantLock`, or a wrapping counter on `updateAndGet`, shows the same pre-value on two threads, and a same pre-value alone is not proof. The count it reports is the minimum number of lost writes consistent with the recorded values ("lost at least N"), never a sum over collision groups, which would assume an order the detector never saw. `incrementAndGet()` gives each thread a distinct pre-value and is silent; so is a consistently held monitor, sampled with `Thread.holdsLock`. Inconsistent guarding, or two different monitors, is still reported, and the message says which.
* **Buggy Code**:
  ```java
  int[] hits = {0};
  Runnable onRequest = () -> hits[0] = hits[0] + 1;   // read, add, write - three steps
  ```
* **Fixed Code**:
  ```java
  AtomicInteger hits = new AtomicInteger();
  Runnable onRequest = hits::incrementAndGet;         // one operation, no window
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.lambdaLostUpdateDetector();
  int before = hits[0];
  hits[0] = before + 1;
  d.recordReadModifyWrite(task, "hits", before, before + 1, Thread.currentThread());
  ```

## Phase 23: Virtual-Thread Scale Hazards (1.9.5+)

The JEP 444 first-order hazards are covered by Phases 6 and 21 — pinning, pooling, CPU-bound
tasks, carrier exhaustion, context leaks, thread-per-task. These three are the second-order set:
failures that only appear once virtual threads make concurrency unbounded, and that the older
detectors cannot see because they were written when the thread count was the pool size.
`LOCK_CONTENTION`, `THREAD_LEAK` and `THREAD_STARVATION` contain no reference to
`Thread.isVirtual()` at all. Examples [143–145](../../examples/README.md).

### 140. Virtual Thread Resource Saturation
* **Severity**: `HIGH`
* **Trust tier**: **fact** — peak *virtual* waiters versus declared capacity, both counts; a fan-out bounded by a semaphore of the resource's own size is silent, and so is a queue that platform threads made.
* **Description**: Detects an unbounded virtual-thread fan-out queueing on a bounded resource. A fixed pool of eight platform threads could never ask for a ninth connection, so the pool size was admission control that nobody wrote down; removing the pool removes it, while the connection pool, the rate limiter and the downstream service stay as bounded as they were. JEP 444's own advice is to limit the resource with a `Semaphore` rather than to pool the threads. The count compared against the capacity is the peak number of virtual threads waiting at once, so a platform-only workload, or a platform burst with a virtual thread passing through at some other moment, is out of scope: a bounded pool cannot produce this, and `THREAD_POOL_DEADLOCK` covers that ground. There is deliberately no "holders exceeded the capacity" finding: a caller returns the resource and then records having done so, and in that window the next caller can legitimately be granted it, so an observed count above the capacity is instrumentation skew as often as a real breach.
* **Buggy Code**:
  ```java
  var pool = Executors.newVirtualThreadPerTaskExecutor();
  for (Request r : requests) pool.submit(() -> { try (var c = ds.getConnection()) { handle(r, c); } });
  ```
* **Fixed Code**:
  ```java
  Semaphore admission = new Semaphore(ds.getMaximumPoolSize());   // bound the resource, not the threads
  pool.submit(() -> { admission.acquire(); try (var c = ds.getConnection()) { handle(r, c); }
                      finally { admission.release(); } });
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.vthreadResourceSaturationDetector();
  d.registerResource("connections", ds.getMaximumPoolSize());
  d.recordAcquireStart("connections", Thread.currentThread());       // inside the semaphore, before getConnection()
  d.recordAcquired("connections", Thread.currentThread());           // got one ...
  d.recordAcquireAbandoned("connections", Thread.currentThread());   // ... or gave up (timeout): out of the queue
  ```

### 141. Virtual Thread Monitor Serialization
* **Severity**: `HIGH`
* **Trust tier**: **fact** — peak number of *virtual* threads queued at once and the number of distinct virtual waiters, both counts; a critical section nobody queues on is silent, and so is a queue that platform threads made.
* **Description**: Detects a monitor serialising a large virtual-thread fan-out — the hazard JEP 491 left behind. Before JDK 24 a blocking `synchronized` pinned its virtual thread to a carrier and `VIRTUAL_THREAD_PINNING` reported it; that detector now correctly marks monitor events obsolete from JDK 24 on. The throughput limit did not go with the pinning: `synchronized` still admits one thread at a time, and with the pool gone nothing bounds how many arrive. It is easy to miss precisely because the fix landed, since a JDK 24 upgrade reads as "the pinning warnings went away". The report states which side of JDK 24 it is on and points at the pinning detector below it. The count compared against the threshold is the peak number of virtual threads queued at once, so a queue that platform threads made, with a virtual thread or two passing through at other moments, is `LOCK_CONTENTION`'s finding and not this one. `LOCK_CONTENTION` cannot make this call the other way — it has no notion of a virtual thread.
* **Buggy Code**:
  ```java
  synchronized (lock) { var v = cache.get(k); if (v == null) { v = load(k); cache.put(k, v); } return v; }
  ```
* **Fixed Code**:
  ```java
  return cache.computeIfAbsent(k, this::load);   // admits every thread; or shrink what is under the lock
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.vthreadMonitorSerializationDetector();
  d.recordMonitorEnter(lock, "sessionCache", Thread.currentThread());
  synchronized (lock) { d.recordMonitorAcquired(lock, Thread.currentThread()); ... }
  ```

### 142. ThreadLocal Cache Degradation
* **Severity**: `MEDIUM`
* **Trust tier**: **fact** — distinct instances counted by identity; a shared value, a pooled helper and platform-only usage are all silent.
* **Description**: Detects a `ThreadLocal` that was a cache under a pool and became an allocator under virtual threads. `ThreadLocal<SimpleDateFormat>` is the standard answer to a helper that is not thread-safe, and on a pool it is a good one: eight workers means eight formatters for the life of the process, bounded by the pool, which is why nobody counts them. A thread per task means an instance per task, retained for that thread's life. Nothing fails — the object is still confined to one thread — so the code reads exactly as it did when it was a cache. Distinct from `VIRTUAL_THREAD_CONTEXT_LEAKS`, which counts distinct ThreadLocal *keys* per thread; here there is one key and the question is how many *instances* it produced.
* **Buggy Code**:
  ```java
  static final ThreadLocal<SimpleDateFormat> FORMAT =
          ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));   // one per task now
  ```
* **Fixed Code**:
  ```java
  static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");   // immutable, shared
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.threadLocalCacheDegradationDetector();
  d.recordCachedValue("FORMAT", FORMAT.get(), Thread.currentThread());
  ```

## Phase 24: JDK 26 Structured Concurrency and Lazy Constants

JEP 525 (Structured Concurrency, sixth preview) and JEP 526 (Lazy Constants, second preview) both
moved work from the JDK into the application: a `Joiner` you write, a `Configuration` lambda you
return, a mapping function that runs per element. These four detectors cover the surfaces those
changes created. `STRUCTURED_TASK_SCOPE_MISUSE` still owns the scope's own fork/join/close
lifecycle; nothing here repeats it.

There is no detector for JEP 522 (G1 GC synchronization reduction). It changes how application
threads and GC workers coordinate over the card table, and exposes no API a test can record
against, so a detector for it would be a guess dressed as a measurement.

### 143. Scope Joiner Misuse
* **Severity**: `CRITICAL` / `HIGH` / `MEDIUM` by finding
* **Trust tier**: **fact** — every finding is a recorded count: scopes bound, threads overlapping in `onComplete`, calls seen off the owner thread.
* **Description**: Detects misuse of the `StructuredTaskScope.Joiner` contract. A joiner is called from two directions at once: `onComplete` runs on whichever subtask thread finished, concurrently with its peers, while `result()` and the JDK 26 `onTimeout()` run on the owner. A joiner accumulating into a plain `ArrayList` is a data race no amount of correct scope usage removes. JEP 525's `onTimeout()` makes it worse by design — returning a partial result is now the recommended pattern, so an accumulator that used to be discarded on timeout is now read while cancelled subtasks are still writing to it. Also flags a joiner reused across scopes (it carries the previous run's state), and forking after `onComplete` asked for the short-circuit.
* **Buggy Code**:
  ```java
  final class Collecting<T> implements Joiner<T, List<T>> {
      private final List<T> done = new ArrayList<>();          // written from subtask threads
      public boolean onComplete(Subtask<? extends T> st) { done.add(st.get()); return false; }
      public List<T> onTimeout() { return List.copyOf(done); } // read on the owner, mid-write
  }
  ```
* **Fixed Code**:
  ```java
  final class Collecting<T> implements Joiner<T, List<T>> {
      private final Queue<T> done = new ConcurrentLinkedQueue<>();
      public boolean onComplete(Subtask<? extends T> st) { done.add(st.get()); return false; }
      public List<T> onTimeout() { return List.copyOf(done); }
  }
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.scopeJoinerMisuseDetector();
  d.recordJoinerBound(joiner, "orders", scopeId, Thread.currentThread());
  d.recordOnCompleteEnter(joiner, Thread.currentThread());
  d.recordAccumulate(joiner, Thread.currentThread());
  d.recordOnCompleteExit(joiner, Thread.currentThread(), false);
  ```

### 144. Scope Configuration Misuse
* **Severity**: `CRITICAL` / `HIGH` / `MEDIUM` / `LOW` by finding
* **Trust tier**: **fact** — requested settings are compared against effective ones, and scope lifetimes are ordered by a sequence counter rather than the clock.
* **Description**: Detects misuse of the `UnaryOperator<Configuration>` lambda JEP 525 introduced in place of the scope constructors. `Configuration` is immutable and every `withX` returns a new instance, so a lambda that does not hand back the value it derived from its own parameter applies nothing — the scope silently has no deadline, and one hung subtask hangs the test forever. Also flags a non-positive timeout (the timeout path becomes the only path), a wide fan-out with no deadline at all, a scope whose every `join()` expired, one `ThreadFactory` configured on scopes that are alive at the same time, and duplicate `withName` values among live scopes.
* **Buggy Code**:
  ```java
  var base = StructuredTaskScope.Configuration.defaults();
  try (var scope = StructuredTaskScope.open(joiner,
          cfg -> { cfg.withTimeout(Duration.ofSeconds(3)); return base; })) {   // timeout dropped
  ```
* **Fixed Code**:
  ```java
  try (var scope = StructuredTaskScope.open(joiner,
          cfg -> cfg.withTimeout(Duration.ofSeconds(3)).withName("order-fetcher"))) {
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.scopeConfigurationMisuseDetector();
  d.recordScopeOpened(scopeId, "order-fetcher", 3000L, threadFactory, Thread.currentThread());
  d.recordEffectiveConfiguration(scopeId, effectiveName, effectiveTimeoutMillis);
  d.recordJoinOutcome(scopeId, timedOut);
  d.recordScopeClosed(scopeId);
  ```

### 145. Scope Result Escape
* **Severity**: `CRITICAL` / `HIGH` / `MEDIUM` by finding
* **Trust tier**: **fact** — reads are ordered against the scope's close by a sequence counter, and the reading thread is compared against the recorded owner.
* **Description**: Detects a scope's results outliving the scope. JDK 25's joiners returned a `Stream<Subtask<T>>`; a stream is lazy and single-use, so holding one past `close()` failed early and loudly. JDK 26 returns a `List`, which is the ergonomic win everyone wanted and also a handle that stores happily in a field. Structured concurrency's guarantee is that subtasks do not outlive their scope — a handle read after `close()`, or on a thread that never called `join()`, has no happens-before edge to the writes it points at. Also flags publishing the handle before `join()` returned, and mutating the unmodifiable result list. Distinct from `STRUCTURED_TASK_SCOPE_MISUSE`, which covers reading a result *too early*; this one covers too late, or on the wrong thread.
* **Buggy Code**:
  ```java
  List<Subtask<Order>> results;
  try (var scope = StructuredTaskScope.open(Joiner.<Order>allSuccessfulOrThrow())) {
      scope.fork(this::fetchA);
      results = scope.join();
  }
  return results.get(0).get();          // the scope is gone
  ```
* **Fixed Code**:
  ```java
  try (var scope = StructuredTaskScope.open(Joiner.<Order>allSuccessfulOrThrow())) {
      scope.fork(this::fetchA);
      return scope.join().get(0).get();  // read inside the structure
  }
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.scopeResultEscapeDetector();
  d.recordScopeOpened(scopeId, Thread.currentThread());
  d.recordJoinCompleted(scopeId);
  d.recordResultHandle(results, "orders", scopeId);
  d.recordScopeClosed(scopeId);
  d.recordHandleRead(results, Thread.currentThread());
  ```

### 146. Lazy Collection Misuse
* **Severity**: `CRITICAL` / `HIGH` / `LOW` by finding
* **Trust tier**: **fact** — computations, values and dependency edges are all recorded; the cycle finding is a walk over edges that were actually observed.
* **Description**: Detects misuse of `List.ofLazy(size, fn)` and `Map.ofLazy(keys, fn)`, the lazy collections JEP 526 added beside `LazyConstant`. Where `LAZY_CONSTANT_MISUSE` covers one holder with one supplier, a lazy collection is *n* independent at-most-once computations sharing one mapping function, each running on whichever thread asked for that element first. That makes possible a failure a single constant cannot have: a mapping function that reaches back into its own collection couples two elements, and if the dependency runs both ways, two threads each hold one element and wait for the other — a deadlock the JDK breaks with `IllegalStateException` when the cycle is on one thread, and does not break when it is spread across two. Also flags a mapping function that ran twice, disagreed with itself, or returned `null` (which JDK 26 rejects), plus warnings for nested computation and for many readers queueing on one slow element.
* **Buggy Code**:
  ```java
  static final List<Cell> GRID = List.ofLazy(64, i ->
          new Cell(i, GRID.get((i + 1) % 64).weight()));   // every element waits on the next
  ```
* **Fixed Code**:
  ```java
  static final int[] WEIGHTS = computeWeights(64);          // eager base layer, no coupling
  static final List<Cell> GRID = List.ofLazy(64, i -> new Cell(i, WEIGHTS[(i + 1) % 64]));
  ```
* **Detect**:
  ```java
  var d = AsyncTestContext.lazyCollectionMisuseDetector();
  d.recordGet("GRID", i, Thread.currentThread());
  d.recordComputeStart("GRID", i, Thread.currentThread());
  d.recordComputeEnd("GRID", i, Thread.currentThread(), value);
  ```
