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
passes `null`, which the analysis reads as "not known" rather than as evidence. Only
identity hashes leave the call — neither the receiver nor the stored value is retained.

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
`Collection.add/remove/contains/clear`, `List.get/set`, `Queue.offer/poll/peek`. Each call becomes a
call to a hook that records and then performs the original operation, so behaviour is unchanged;
`CollectionWeavingEndToEndTest` pins that a woven program still computes the same values.

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
  queue it was polled from is that owner, from the `collections=true` hooks for `Queue.offer`/`add`
  and `BlockingQueue.offer`/`put`, and from `fields=true` for a reference slot (`set`, `lazySet`,
  `setRelease` or `compareAndSet` on an `AtomicReference`, an `AtomicReferenceFieldUpdater`, an
  `AtomicReferenceArray` or an instance-field `VarHandle`) and a JCTools `offer`/`relaxedOffer`.
  `BlockingQueue.take` is a take like `poll`, and `drainTo` drops every offer recorded into the
  drained queue, so an element taken or drained and put back through an unwoven call cannot keep
  naming its first offerer ([#664](https://github.com/PIsberg/async-test-lib/issues/664)); a
  removal not reported as a take (`remove`, `removeIf`, unwoven code) still can. Only when no
  such offer was recorded does every thread get that benefit: an element that entered through an
  unwoven method (`addAll`, `Deque.offerFirst`, `push`, or code outside `includes`), or a
  `VarHandle` take from a static field or an array element
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
