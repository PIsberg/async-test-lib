# Attaching the agent

Part of the [Agent Instrumentation Guide](../AGENT.md).

## 3. How to attach (all three ways)

The agent JAR (`async-test-agent`, not the library JAR — the manifest moved there in the
module split) is the agent-capable artifact: its `MANIFEST.MF` declares `Premain-Class`,
`Agent-Class`, `Can-Retransform-Classes: true`, and `Can-Redefine-Classes: true`.

### 3.1 Launch flag (static attach), plain

```
-javaagent:async-test-agent-<version>.jar
```

Routes through `AsyncTestAgent.premain(String, Instrumentation)` before `main()` runs. Every
class loaded afterwards is woven at load time. `premain` never retransforms — it does not
need to, because nothing has loaded yet.

### 3.2 Launch flag with arguments

```
-javaagent:async-test-agent-<version>.jar=includes=com.myapp;excludes=com.myapp.dto,debug=true
```

Everything after the `=` is the `agentArgs` string, parsed by `AgentOptions`. The grammar:

| Key | Value | Effect |
|-----|-------|--------|
| `includes` | one or more name prefixes | Instrument **only** types whose fully-qualified name starts with one of the prefixes (narrows the positive match). |
| `excludes` | one or more name prefixes | **Never** instrument types whose name starts with one of the prefixes (appended to the built-in ignore matcher). |
| `debug` | `true` / `false` | `debug=true` turns on verbose diagnostics (see [Diagnostics](diagnostics.md#6-diagnostics)). Any other value, or absence, keeps the default errors-only logging. Case-insensitive. |
| `fields` | `true` / `false` | `fields=true` also weaves **direct field instructions**, so a field touched only inside a method body — the `count++` in an `increment()` — produces events. Off by default; see below. Case-insensitive. |
| `collections` | `true` / `false` | `collections=true` rewrites the collection calls an instrumented type makes, so the **collection instance** reaches the detectors that are keyed by instance. It is what makes a class whose state lives in a `HashMap` visible at all. Off by default; see below. Case-insensitive. |

**Separators and multi-values.** Entries are separated by `,` **or** `;`. A bare token (no
`=`) is appended to the **most recently named key**, so a single key can carry several
values:

```
includes=com.myapp;com.other        # two include roots
includes=com.myapp,debug=true        # include + a flag
```

**`fields=true`: what it buys and what it costs.** Accessor weaving binds `Advice` to method
entry, so it can only see a field reached *through* a getter or setter. A bare `count++` inside a
method compiles to `GETFIELD` / `PUTFIELD` with no method call to bind to, and that is the most
common shape of a real race — it is why the README's own counter example reported nothing before
this option existed. `fields=true` instruments the instruction stream instead, inserting a
stack-neutral, branch-free observation call before each field instruction.

The call carries the receiver, so the analysis can tell six threads racing on one object from six
threads each using their own, and — for a store of a reference type — the value being stored. That
last one is what lets the atomicity model tell an idempotent value apart from a side effect: a
double-submit converges on its field exactly like a view cache does, and the difference is only
visible in what the stored object does afterwards
([#326](https://github.com/PIsberg/async-test-lib/issues/326)). An instance reference store is the
shape where the value is already on the operand stack in argument order, so `DUP2` reaches it in
two instructions with nothing to undo. A static reference store needs one instruction more,
`DUP; LDC class; SWAP`, because the value sits alone with the class constant pushed above it
([#337](https://github.com/PIsberg/async-test-lib/issues/337)); without that, class-scope
lazy-init was the one double-submit shape the value evidence could not reach. Every other shape
passes `null`, which the analysis reads as "not known" rather than as evidence. The stored value
leaves the call as an identity hash only. The receiver also travels as itself, because two live
objects can share an identity hash and the atomicity model groups accesses per object: the ring
buffer lends it to the drain for one callback and then clears the slot, and the model keeps it
only weakly, so neither is retained by the telemetry path.

It is off by default because the cost scales with the instrumented surface, not with the number of
accessors: every field read and write in every matched class emits an event. Pair it with
`includes=` so the weaving lands on the code under test rather than on the whole classpath:

```
-javaagent:async-test-agent-<version>.jar=includes=com.myapp,fields=true
```

Fields owned by the JDK, Byte Buddy or this library are never woven, whatever the includes say —
without that, a `System.out` reference in user code would emit on every call, and a field access
inside the telemetry sink would recurse. Static initialisers are skipped too, because emitting
from `<clinit>` can force the telemetry classes to initialise inside another class's
initialisation, and circular class initialisation deadlocks rather than failing.

**`collections=true`: reaching state a class does not own.**

Field weaving makes a class's own fields observable. It does nothing for a class that keeps its
state in a collection:

```java
class Registry {
    private final Map<String, Integer> entries = new HashMap<>();   // final: no PUTFIELD to weave

    void record(String key) {                                        // the racing write happens
        entries.put(key, entries.getOrDefault(key, 0) + 1);          // inside java.util.HashMap
    }
}
```

`entries` is assigned once, so there is no field instruction to observe, and the write that races
happens inside `java.util.HashMap`, which is on the ignore list and always will be. Under
`fields=true` this class produces no finding no matter how many threads collide on it. This is not
a corner case: it was measured on real libraries, where three of nine documented-not-thread-safe
classes were silent for exactly this reason ([corpus eval](../analysis/corpus-eval.md)).

`collections=true` rewrites the collection call itself, so the map instance reaches
`SharedCollectionDetector` and its instance-keyed siblings:

```
-javaagent:async-test-agent-<version>.jar=includes=com.myapp,collections=true
```

What is rewritten is an explicit table in `CollectionAccessWeaver`: `Map.put/get/remove/containsKey`,
`Collection.add/addAll/remove/removeIf/contains/clear`, `List.get/set`, `Queue.offer/poll/peek/remove`,
and the `Deque` end-specific offers and takes. Each call becomes a
call to a hook that records and then performs the original operation, so behaviour is unchanged;
`CollectionWeavingEndToEndTest` pins that a woven program still computes the same values.

The same option substitutes `Object.wait`, `notify` and `notifyAll` (#694), which is what feeds
`MissedSignalDetector` without a recorded call: the hooks run with the monitor held, so a notify is
judged against the threads really inside `wait()`. The weaver also inserts one call in front of
the backward jump that closes a loop around a woven wait, and a wait whose thread reaches it
after waking is a `while (!ready)` loop's and is never reported. A wait with no such jump around it
is an `if`'s.

The whole class is read before any of it is emitted, so the wait may sit in a helper the loop calls
(#707); a helper in another class is recognised whether it is inherited from a supertype or reached
through an interface, which the call site names instead of the class that waits (#709).

Which of the two classes is woven first used to decide it, and load-time weaving delivers the
unhelpful order almost every time: a class named only inside a method body is resolved lazily, on
first execution of the instruction naming it, so when the caller is woven at load its helper has
not been loaded at all. Measured on one pair of fixtures in one JVM before the fix, caller first
gave 0 marks where helpers first gave 1. Since #715 a call the weaver cannot resolve is remembered
against the signature it named, and a later class registering that signature has the callers
retransformed, which runs the weaver over them again with the helper now in the index.

The window is narrowed, not closed. The retransformation is handed to a daemon thread rather than
run inside the transform that triggers it, because a transform runs while a class is being defined
and calling back into `retransformClasses` from there re-enters the transformer chain under the
defining thread's locks. A loop that executes before the retransformation lands still runs unmarked
code, and a finding recorded in that window is still a finding. It is one of the readings keeping
`MISSED_SIGNAL` at `PROMPT`.

A jump counts as closing the loop when it is an unconditional `goto` and something is read
between the loop's head and the wait, which is how javac and kotlinc close a `while`.
`do { wait(); } while (!ready)` closes with the predicate test itself,
and a loop closed by `continue` reads nothing before it blocks; both enter `wait` before they have
read the predicate, and both are reported.

A compiler that rotates loops emits `goto test; body; test: if (...) goto body`, so its `while`
closes with a conditional jump and puts the test after the wait. That shape counts too, when the
loop head is entered by a `goto` that lands between the wait and the back-edge, which is what a
rotated loop has and a `do`/`while` has not (#710). ECJ rotates and javac does not, so without this
a correct poll compiled by Eclipse would be reported; `MissedSignalRotatedLoopWeavingTest` compiles
the shapes with the real ECJ and reads the marks back. kotlinc emits the javac shape for both
`while` and `do`/`while`, first checked by hand at 2.4.10 and gated since #714 by a pair in
`consumer-fixture-langs/kotlin`: a Kotlin `while` poll that must stay silent and a Kotlin
`do`/`while` that must be reported, run against the agent with `-javaagent`. The gate lives there
rather than beside the ECJ one because a mark-level gate would need a Kotlin compiler on
`async-test-agent`'s test classpath, and the language toolchains are deliberately confined to that
fixture's own pom. It asserts on the finding instead of on the marks, which is what a user sees;
the firing half is what stops the silent half passing by nothing being woven at all.

**What the hold costs.** Reading the whole class before emitting any of it means holding every
method of every class `collections=true` weaves, including the great majority that never call
`wait`, as a tape of replayable actions (#711). Measured against the streaming tables, which emit
each method as it arrives: one monitor-table pass over six ASM and Byte Buddy classes, 119,871
class-file bytes, allocates 3,218,824 bytes against 1,648,720, a ratio of 1.95x, and takes 13-14 ms
against 9-11 ms on JDK 26 and 9 ms against 8 ms on JDK 21. That is roughly 13 extra bytes of
short-lived garbage per class-file byte and roughly 3 ms extra per 100 kB of bytecode, paid once
per class while the agent attaches and never inside a round.
`MonitorWeavingAllocationBudgetTest` holds the ratio at 2.2x, so a change in the shape of the hold
fails a build instead of being absorbed.

Holding the methods in ASM's own encoding instead, a scratch `ClassWriter` written as they arrive
and read back through a `ClassReader` at the end, was built and measured on the same classes at
2.14x: the second reader rebuilds the whole constant pool as strings, which costs more than the
captured lambdas it saves. It is not in the tree, and the measurement is recorded here so the same
afternoon is not spent twice.

**Ordering the lockset cannot see (1.12.3).** The same hooks feed the shared happens-before model,
`HappensBefore`, which `RaceConditionDetector` and `AtomicityValidator` consult before they report
a round: a round whose every conflicting pair the model orders is not reported. The detectors on
the shared per-instance round verdict (`SelfGuard`, the `Shared*` family among them) consult it
too: a thread whose use of the instance the model orders after the previous thread's takes it
over rather than sharing it, while two threads using it at once still report. For the field
stream the accessing thread's clock is stamped at publish time and travels through the ring with
the event, since the drain thread's own clock orders nothing. The edges are the ones the Java memory
model names for the woven calls: an element offered to and taken from a `java.util.concurrent`
queue (or any `BlockingQueue` or `ConcurrentMap`, and the synchronized wrappers), a value put into
and read back from such a map, `CountDownLatch.countDown` and an `await` that reached zero,
`Semaphore.release` and an acquire that took a permit, and `Thread.start` and a `Thread.join` that
returned with the thread finished (every `join` overload is substituted for this). With
`fields=true`, a volatile write releases its object and a later access the weaver marks as
following a volatile read of the same object acquires it. An `ArrayDeque` or a `HashMap` promises
nothing and gives no edge. A lock hand-off is deliberately not an edge: the lockset judges locking,
and ordering it by the one schedule a run took would hide what another schedule exposes.

Three limits worth knowing before switching it on:

- **Guarding works, and has to.** Monitor weaving is installed alongside, so a collection touched
  only inside a `synchronized` block reports nothing even though the test never declared the lock.
  Lock weaving rides along too: a `Lock.lock()`/`unlock()` call site in woven code feeds the same
  lockset, `ReadWriteLock.readLock()`/`writeLock()` call sites resolve each view to its owner,
  in shared mode for the read side, and `StampedLock`'s own call shapes are modelled the same way
  (write stamps exclusive, read stamps shared, optimistic reads deliberately nothing), so a
  collection guarded by a `ReentrantLock`, a `ReentrantReadWriteLock` or a `StampedLock` reports
  nothing either. A lock acquired only inside unwoven code still needs
  `AsyncTestContext.holdingLock(...)`.
- **Spinlocks and hand-offs are exclusion too (with `fields=true`).** A won
  `VarHandle.compareAndSet(this, 0, 1)` on an `int` field is a spinlock: the weaver replaces the
  call with a hook that performs it and declares a lock on that receiver's flag, released by a
  `compareAndSet(this, 1, 0)`, an `int` `set` through the handle, or a plain write to the field by
  the holder. A volatile field replaced only while it is held is safe publication, which is how
  Caffeine's `StripedBuffer` table reads now (#554). The same holds for an
  `AtomicIntegerFieldUpdater` bound by a `newUpdater` call the weaver saw (`compareAndSet`,
  released by the swap back, `set`, `lazySet` or the holder's write), and for an `AtomicBoolean` or
  `AtomicInteger` that is the lock itself (`compareAndSet(false, true)`, `!getAndSet(true)`,
  `compareAndSet(0, 1)`, released by the swap back, `set` or `lazySet`). The value-returning
  releases are substituted too (#658): `getAndSet`, `getAndAdd`, `compareAndExchange` and the weak
  swaps on the handle, `getAndSet`, `getAndAdd`, `addAndGet`, `getAndDecrement`, `decrementAndGet`
  and `weakCompareAndSet` on the updater and the `AtomicInteger`, and `compareAndExchange` and the
  weak swaps on both atomics. A handle bound before the agent attached is resolved from its own
  descriptor (#558), and an updater bound before it from its own target class and field offset,
  which the agent opens `java.util.concurrent.atomic` to read (#659), to the module of the library
  copy each woven loader resolves and to nothing else (#668).
  A spinlock is never trusted past what its flag says: it counts as held only while the flag
  still reads locked and this thread is its last observed winner, re-checked whenever the lockset
  is read. A release through a call the weaver does not substitute (`Unsafe`, JNI, reflection, a
  `VarHandle` call site with an `Object` or `long` result, unwoven code) therefore drops the lock before the
  next access is recorded instead of leaving it declared, which would make every later write on
  that thread look guarded. One narrow window survives for those forms: a release landing between
  another thread's check of the flag and its swap leaves the old holder passing that re-check until
  the new holder records itself (#658; the forms are listed in `SpinLocks`). Separately, the
  object a reference `getAndSet` returns, or a `Queue.poll` or JCTools `MessagePassingQueue`
  `poll`/`relaxedPoll` hands back, is reported as taken: it
  starts a new ownership generation, exclusive to the taker until another thread touches it, and
  locks only have to agree within a generation. That is netty's chunk moving between magazines
  (#555). Another thread's access inside the generation the receiver is still in withdraws the
  taker's exclusivity for that whole generation, including accesses the taker made before it, so
  an alias kept from before the take cannot hide behind the order its access was published in
  (#559). In a generation a later take closed, an access withdraws it only when its thread neither
  took that generation nor owned the one before it: the previous owner's late access is a hand-off,
  and when no access showed who owned generation 0, the thread that offered the object to the
  queue it was polled from is that owner, from the `collections=true` hooks for `Queue.offer`/`add`,
  `Collection.addAll` on a queue, `Deque.offerFirst`/`offerLast`/`addFirst`/`addLast`/`push`,
  `BlockingDeque.putFirst`/`putLast` and its timed `offerFirst`/`offerLast`
  ([#692](https://github.com/PIsberg/async-test-lib/issues/692))
  and `BlockingQueue.offer`/`put`, and from `fields=true` for a reference slot (`set`, `lazySet`,
  `setRelease` or `compareAndSet` on an `AtomicReference`, an `AtomicReferenceFieldUpdater`, an
  `AtomicReferenceArray` or a `VarHandle` (instance field, static field or array element)) and a
  JCTools `offer`/`relaxedOffer`.
  `BlockingQueue.take`, `Queue.remove()`, `remove(Object)` on a queue,
  `Deque.pollFirst`/`pollLast`/`removeFirst`/`removeLast`/`pop`, and `BlockingDeque.takeFirst`/
  `takeLast` with its timed `pollFirst`/`pollLast` are takes like `poll`, and `drainTo`
  and `removeIf` on a queue drop every offer recorded into it, so an element taken or drained and
  put back through an unwoven call cannot keep naming its first offerer
  ([#664](https://github.com/PIsberg/async-test-lib/issues/664),
  [#692](https://github.com/PIsberg/async-test-lib/issues/692)); a removal in unwoven code, or
  through an iterator, still can. Only when no such offer was recorded does every thread get that
  benefit: an element that entered through code outside `includes`
  ([#630](https://github.com/PIsberg/async-test-lib/issues/630)). Spinlock shapes not modelled,
  so writes under them still report: `Unsafe.compareAndSwapInt`, and an
  `AtomicIntegerFieldUpdater` created before the agent attached whose target cannot be read (a
  library copy in a loader outside the woven loader chains, a named module, or a JDK whose updater
  implementation changed shape).
- **Thread-safe types are skipped.** A receiver from `java.util.concurrent`, a
  `Collections.synchronizedX` wrapper, a `Hashtable` or a `Vector` synchronizes where nothing can
  be woven, so recording it would report every shared use. Those calls are delegated and never
  recorded. So is any receiver that inherits no instance state from a bootstrap-loaded class: its
  fields live in weavable code, where the field weaver already watches them, and a stateless
  implementation such as Guava's discarding queue has nothing a write could corrupt.
- **Threads are counted per round.** Rounds are ordered by the runner, so two accesses from
  different rounds cannot race; a collection written by one (virtual) thread per round is
  sequential, and a finding names the widest single round.
- **`super` calls keep their dispatch.** Only virtual and interface invocations are rewritten. A
  decorator's `super.get(...)` must stay an `INVOKESPECIAL`, or the substitution would re-dispatch
  virtually into the override and recurse.

**Reaching it without a `-javaagent` path.** `-Dasynctest.agent=fields=true` makes the runner
attach the agent itself at the start of the run, so you do not have to resolve the jar's path in
your build. It needs the `async-test-agent` artifact on the test classpath; if it is missing, or
the JVM forbids self-attachment, the runner logs `runner.agent.attach.failed` once and continues
without instrumentation rather than failing the suite.

**Robustness.** Parsing never throws (an exception in `premain` would abort JVM startup).
Whitespace is trimmed, empty entries are skipped, keys are matched **case-insensitively**,
and **unknown keys are ignored**. A `null` or blank argument leaves the default behavior
(instrument every non-ignored class) fully intact.

### 3.3 Runtime self-attach (no launch flag)

```java
import se.deversity.asynctest.agent.AsyncTestAgent;

@BeforeAll
static void attachAgent() {
    AsyncTestAgent.selfAttach("includes=com.myapp");
}
```

`selfAttach()` obtains an `Instrumentation` handle via Byte Buddy's
`byte-buddy-agent` (`ByteBuddyAgent.install()`) and installs the same transformer used by
`premain` — no `-javaagent:` launch-flag edit required. `selfAttach()` is equivalent to
`selfAttach(null)` (instrument everything not ignored).

**When to prefer it.** Use self-attach when you cannot control JVM launch flags (for example,
an IDE run configuration or a shared surefire setup), and want to scope instrumentation to a
single test class's `@BeforeAll`.

**Retransformation caveat (already-loaded classes).** Unlike `premain`, self-attach happens
*after* application classes may already be loaded. It therefore installs with
`RedefinitionStrategy.RETRANSFORMATION` + `disableClassFormatChanges()`. Because the injected
`@Advice` only inlines a method-entry prologue — it adds no fields, methods, or interfaces —
the class schema is unchanged and retransformation is safe. **Verified empirically**
(`SelfAttachTest`): accessors of classes loaded *before* the attach are re-woven in place,
exactly like classes loaded afterwards, and so are the classes the attach loads while it runs.

**A class the JVM refuses does not cost the others their weaving.** `retransformClasses` is
all-or-nothing per call, and some classes cannot be re-verified at all: Netty's optional logger
adapters fail with `InternalError: class redefinition failed: invalid class` when log4j is absent
from the classpath, because re-verifying them needs a type that is not there. Byte Buddy's default
passes every loaded class in one such call and its default listener swallows the failure, so a
single refused class used to leave the agent installed but weaving nothing that had already
loaded, silently. The agent therefore retransforms in fixed batches, halves a failing batch until
the refused class stands alone, and prints one line naming it:

```
[ASYNC-TEST-AGENT] Could not re-weave already-loaded class io.netty.util.internal.logging.Log4JLogger: java.lang.InternalError: class redefinition failed: invalid class
```

If detectors go quiet after an attach, that line is the first thing to look for. Pinned by
`RetransformBatchIsolationTest`; measured on a real classpath in
[the corpus eval](../analysis/corpus-eval.md#what-the-corpus-taught-the-model-in-five-rounds), where
the defect cost 874 of 1074 instrumented classes and the whole documented-unsafe detection column.

**The attach loads classes of its own, and those used to be lost.** There are three sets, not
two. Retransformation covers what is already loaded at the moment of the attach; load-time weaving
covers everything loaded afterwards; and in between sits a set nobody thinks about — the classes
the retransformation pass itself loads while it runs. Byte Buddy describes an already-loaded class
through the reflection API, and reflection eagerly resolves every field and method signature type,
so describing a class *loads the types it names*. Those loads happen on the attaching thread while
Byte Buddy holds its circularity lock, where the transformer declines without calling any listener,
and the default `DiscoveryStrategy.SinglePass` took its snapshot of the loaded classes before they
existed. Woven by nothing, with no error and no log line.

It was expensive and it looked like file order. The corpus eval measured it: running its two test
classes in the other order moved detection of documented-unsafe subjects from 20 of 20 to 6 of 20,
because the classes that went missing were the test class's own field types — `MutableInt` and
`MutableLong` were never consulted while 56 other `commons-lang3` types wove normally
([#316](https://github.com/PIsberg/async-test-lib/issues/316),
[#321](https://github.com/PIsberg/async-test-lib/issues/321)).

The agent now discovers with `RedefinitionStrategy.DiscoveryStrategy.Reiterating`, which re-queries
the loaded set until it stops growing and so picks up whatever the pass loaded. Pinned by
`SelfAttachTest.classLoadedByTheAttachItself_isStillWoven`, which fails on the unfixed agent.

**And if a class is ever missed again, the agent says so.** The reason the above took an afternoon
to find is that there was no line to look for. After a dynamic attach the agent now diffs the type
names the transformer was handed against `getAllLoadedClasses()`, filtered by the same ignore and
`includes` matchers the install used, and names whatever is left:

```
[ASYNC-TEST-AGENT] 1 already-loaded class(es) were never handed to the transformer, so they are
woven by nothing and invisible to every agent-fed detector. ...
[ASYNC-TEST-AGENT]   com.example.probe.Payload
```

A healthy attach prints nothing. Pinned by `AttachCoverageReportTest`; `premain` skips the check,
where the set is empty by construction.

If a suite's results have to be comparable across machines, attach with the launch flag anyway:
`premain` installs before any application class exists, so nothing depends on which test runs
first and the whole question above never arises. `corpus-eval/pom.xml` does exactly this and gates
on it.

**Idempotency and interaction with `-javaagent`.** All three entry points share a single
at-most-once install gate (an `AtomicBoolean` CAS). If the agent was already attached — via a
launch flag or a prior `selfAttach` — the call returns immediately without attaching again or
double-weaving. `selfAttach()` is therefore safe to call unconditionally even when a
`-javaagent:` flag may already be present, and safe to call concurrently from multiple
threads.

**If the JVM forbids self-attach.** Self-attachment is disabled by default on JDK 9+ unless
the JVM was started with `-Djdk.attach.allowAttachSelf=true`. When attachment is refused,
`selfAttach` throws an `IllegalStateException` (it does **not** swallow the failure) whose
message directs you to set that flag or fall back to the `-javaagent:` launch flag.

### 3.4 Build snippets for self-attach

Self-attach needs `-Djdk.attach.allowAttachSelf=true` on the test JVM.

**Maven (surefire):**

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <!-- @{argLine} preserves JaCoCo's late-bound agent argLine -->
    <argLine>@{argLine} -Djdk.attach.allowAttachSelf=true</argLine>
  </configuration>
</plugin>
```

**Gradle (Kotlin DSL):**

```kotlin
tasks.test {
    // Allow ByteBuddyAgent.install() to self-attach (disabled by default on JDK 9+).
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
}
```

For **static** attach via `-javaagent` instead, point surefire's `argLine` / Gradle's
`jvmArgs` at the built agent JAR: `-javaagent:/path/to/async-test-agent-<version>.jar`.
