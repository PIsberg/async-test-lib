# Agent limitations

Part of the [Agent Instrumentation Guide](../AGENT.md).

## 7. Limitations (honest)

- **Method granularity only.** The agent records that a getter/setter ran and on which thread.
  It has **no field value** — so any analysis that needs values (e.g. `VisibilityMonitor`'s
  value-divergence check) cannot be driven by field-access telemetry. This is why `TelemetryBridge` routes
  field-access events to `AtomicityValidator` only, whereas the weavers for collections, locks, monitors,
  constructors, and shared instances feed the other agent-backed detectors directly (see
  [What feeds each detector](../DETECTOR_CATALOG.md#what-feeds-each-detector)).

- **Only getters/setters are intercepted, unless `fields=true`.** By default matching is
  `ElementMatchers.isGetter()` / `isSetter()`, so a **direct field access** — `this.x = 1` or
  `return x` inside the class, bypassing an accessor — is not intercepted. Supplying
  [`fields=true`](attaching.md#32-launch-flag-with-arguments) removes that limitation by weaving the field
  instructions themselves, at the cost of instrumenting every field access in every matched class.
  Both halves are pinned against the real weaver, on the same fixture, so the boundary is
  specified rather than assumed: `AgentFeedsDetectorEndToEndTest` attaches without the flag and
  requires the directly-mutated field to produce nothing (with a control in the same run proving
  the pipeline was live while that held), and `FieldWeavingEndToEndTest` attaches with it and
  requires the opposite.
- **JDK and framework classes are never instrumented.** Anything under `java.`/`jdk.`/`sun.`/
  `com.sun.`, Byte Buddy, this library, synthetic types other than Kotlin multifile facade parts
  (#1107), and bootstrap-loaded types are excluded by design.
- **A serializable method reference is not woven.** A method reference such as `builder::append`
or `lock::lock` compiles to an `invokedynamic`, and the JVM makes the call from a hidden class
  no agent can weave. The weaver points the lambda factory at the hook instead, so those calls are
  observed like any other (#550), but it leaves a *serializable* lambda (`(Consumer<String> &`
  `Serializable) builder::append`) alone: its generated `$deserializeLambda$` checks the
  implementation method it was compiled against, and a rewritten one would fail to deserialize.
  `MethodReferenceWeavingSparesConfinedUseTest` round-trips one to prove it still works.
- **Drain is best-effort at JVM exit.** The drain thread flushes every 1 ms and once more on
  `stop()`, but events published in the final moments before an abrupt JVM exit may not be
  drained. (Under sustained overload the producer spin-waits rather than dropping events — the
  16 384-slot buffer is not an overwrite buffer — but shutdown timing is still best-effort.)
- **Thread attribution is by the accessing thread.** The event carries the id of the thread
  that ran the accessor. The bridge preserves this by using the explicit-thread-id overload,
  so analysis reflects the worker thread and not the drain thread — but only events from the
  configured worker-thread-id set, and from the threads those workers hand work to, are
  forwarded: a thread a worker starts through a woven `Thread.start`, `Thread.Builder` start or
  `Thread.startVirtualThread` (#834), and a pool thread while it runs a task a worker submitted
  to a JDK executor (#745), registered as a dependent `CompletableFuture` stage, or executed on a
  `ThreadPoolExecutor` (#1155), `ForkJoinPool`, `ScheduledThreadPoolExecutor` or virtual-thread-per-task
  executor (#741), or an `Executors.unconfigurable*` wrapper around one that a woven call made (#1107),
  or a wrapper an `Executors` factory built around one, such as `newSingleThreadExecutor`'s (#1269),
  or helping a parallel stream the run started: the `map`, `filter`, `peek` and `mapToObj` lambdas of a
  `Stream`, `IntStream`, `LongStream` or `DoubleStream`, its `flatMap`, `mapMulti`, `takeWhile`,
  `dropWhile` and `sorted` comparator, and the lambdas of its woven terminal operations (`forEach`,
  `forEachOrdered`, `collect` and a `Collector`'s own supplier, accumulator and combiner, `reduce`,
  `min`, `max`, the matches; #1156, #1172). The actions of one `forEachOrdered` are ordered each
  after the one before it, as the JDK promises, so a list or a field only they touch is not read
  as raced; no other lambda of a pipeline is ordered against its siblings. A pipeline stays followed across every `mapTo*`,
  `mapMultiTo*`, `mapToObj`, `boxed`, `as*Stream`, `sorted`, `distinct`, `limit`, `skip`,
  `parallel()`, `sequential()`, `unordered()` and `onClose`. A pipeline ended by `iterator()` lends its
  lambdas the run inside each `hasNext` and `next`, and orders the caller after them when the call
  returns. A lambda reaches the workers only through a chain of woven calls: one in a pipeline ended
  by `spliterator()` or in unwoven code runs unattributed (#1240).
  Accesses on other application threads during the round, a thread started in unwoven code or a task
  given to `execute` on a `ThreadPoolExecutor` subclass that overrides `execute`, `remove` or
  `shutdownNow`, or a `beforeExecute` or `afterExecute` outside `includes=`, included, are treated as
  noise and dropped; so are the tasks of a pool whose `RejectedExecutionHandler` is a lambda, a method
  reference or a class outside `includes=` (#1238). To observe a `ThreadPoolExecutor`'s task, the run's
  `execute` queues a wrapper around it. The woven `shutdownNow`, `remove` and `getQueue()` give back
  and find your own task, and a woven `beforeExecute`, `afterExecute` or `rejectedExecution` is handed
  it (#1173). The
  shared-instance hooks (a `MessageDigest`, a `Calendar`, a `SimpleDateFormat` and the rest) reach
  the same threads: a thread started through a woven start works for the run of the thread that
  started it until that run is analysed, and a pool thread for the run that handed it a task or
  stage function while it runs it (#834). So do the hooks of the detectors that judge one event on
  the thread it happens on: `System.gc()`, a sleep under a lock, an unlock after a failed `tryLock`,
  a read-to-write lock upgrade, `obtrudeValue` or `obtrudeException`, and a reassigned monitor field
  (#1339), and a `CountDownLatch.await` that timed out. Two detectors that judge what a thread left
  behind follow it with a condition, because a started thread may outlive the run: a
  `ReentrantLock` such a thread still holds is reported only when the lock itself names a holder
  that has ended or idles in its pool, and a `wait` that returned without reaching its loop's
  back-edge only once the thread has ended, idles in its pool or has waited again (#1339). A
  `notify`, a `signal` and a `countDown` on such a thread are recorded as they happen, and a
  `wait` or `await` that began after a signal nobody was waiting for is judged on the same
  condition as the wakeup (`MissedSignalDetector`, `ConditionVariableDetector`). What stays
  unjudged is a helper thread still blocked when the run is analysed, in a `wait`, an `await` or
  a `CountDownLatch.await`: it may be signalled or counted down after the run, so it is neither a
  lost signal nor a missing countdown (`LatchMisuseDetector` records such a thread's await only
  once it has come back). Attribution goes by thread, not by task: a fork-join
  worker that, while waiting inside a task of the run, helps with work the run did not hand it (a
  parallel stream's subtasks, say) has that work counted as the run's, which can only add a
  finding. A thread started in unwoven code is never attributed: telling it apart would need a
  check on every access or an inherited attribution that pool threads would carry into later runs.
