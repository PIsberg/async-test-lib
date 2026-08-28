# Detector-Accuracy Eval: Buggy Code vs the Synchronized Twin

_Branch: `fix/ga-eval-findings` - date: 2026-08-03 - enforced by
`DetectorAccuracyEvalTest` (async-test-lib test suite), so the table below cannot drift
from the code without a red build._

_Updated 2026-08-11 (`feat/detector-lock-awareness`): three detectors gained guard-on-self
synchronization awareness - a `Thread.holdsLock(<tracked instance>)` probe at record time.
The rows, counts and limits below reflect it._

_Updated 2026-08-20 (`fix/detector-fp-surface`): that probe moved into one shared
`SelfGuard.TrackedInstance` and reached 15 more detectors, taking the Shared* family from 2 of
19 to 17 of 19 silent on the correctly guarded twin. The remaining two are contention notes on
thread-safe types, where firing is the correct answer._

_Updated 2026-08-20 (#285): the `FalseSharingDetector` row was prose. The document claimed the
table could not drift without a red build, while `DetectorAccuracyEvalTest` never constructed
that detector, so its experimental gate could have been flipped with nothing going red. Both
directions of the gate are now asserted, and the claim of enforcement is true for every row._

_Updated 2026-08-21 (`feat/detector-trust-tiers`): the trust tier moved out of prose and into
`DetectorTrust`, which classifies all 142 and is enforced by `DetectorTrustCoverageTest`. VERDICT
now requires named both-directions tests that the gate resolves by reflection, so the three rows
that had it kept it and nothing else inherited it by assertion. Three ESSENTIALS-preset detectors
were measured for the first time: `LockLeakDetector` and `CompletableFutureExceptionDetector` hold
in both directions and were promoted, `ConcurrentModificationDetector` fires on correct
thread-safe code and stays at PROMPT. `@AsyncTest(minTrust = ...)` restricts the failOn gate to
the tiers you name._

## What was measured

For each detector: does it fire on genuinely buggy concurrent code (true positive), and
does it stay silent on the correctly synchronized twin of that same code (true negative)?
The twin records the identical event stream through the detector's public recording API
while the underlying code holds a real lock, uses CAS, or orders its locks consistently.
Every recording happens from two live threads released by a CyclicBarrier.

This is a recording-level eval of twenty detectors, one of them behind an experimental gate, not a corpus study
of all 142. It measures the analyzers' models, which is the property that decides whether
a finding on your code means your code is wrong. Since the guard-on-self change the twins
distinguish where the lock lives: guarding with the shared instance's own monitor
(`synchronized (theInstance)`) is now recognized, guarding with any other lock object is
still invisible.

## Results

| Detector | Buggy variant | Synchronized twin | Verdict |
|---|---|---|---|
| RaceConditionDetector | fires | silent under the object's own monitor, and under one declared lock; **fires** under an undeclared lock, and when the two threads declare different locks | lock fingerprint per access; undeclared locks invisible |
| AtomicityValidator | fires | silent when `recordFieldAccessOn` names the owner and one lock covers every access, and for agent-fed accesses under one woven monitor; **fires** through the overloads that carry no lock information | owner-aware path intersects locksets; the agent-fed path compares whole sets by fingerprint, which is coarser |
| SharedMessageDigestDetector | fires | silent with `synchronized(digest)` and with a declared external lock; **fires** with an undeclared one | Eraser lockset via `SelfGuard` |
| SharedStatefulCryptoDetector | fires | silent with `synchronized(mac)` and with a declared external lock; **fires** with an undeclared one | Eraser lockset via `SelfGuard` |
| SharedSecureRandomDetector | n/a (sharing is the documented-safe idiom) | fires at MEDIUM | contention note by design, no longer a HIGH "corruption" claim |
| LockOrderValidator | fires (inversion, no deadlock needed) | silent | genuine both-direction detector |
| AtomicNonAtomicUpdateDetector | fires (get-then-set) | silent (CAS) | genuine both-direction detector |
| DeadlockDetector | fires (real deadlock, zero config; pinned by `DetectionCoverageTest`) | silent | genuine both-direction detector, near-zero FP |
| FalseSharingDetector | silent by default (experimental gate), and reports the pair once the property is set | silent | findings uncorrelated with the phenomenon; opt-in via `-Dasync-test.experimental.false-sharing=true`, and both directions of that gate are now pinned |
| LockLeakDetector | fires (two acquisitions recorded, no release) | silent (every acquire released, nothing held at analysis time) | genuine both-direction detector |
| CompletableFutureExceptionDetector | fires (completed exceptionally with no handler registered) | silent (same failure, handler registered first) | genuine both-direction detector |
| ConcurrentModificationDetector | fires (modification recorded while an iterator is live) | silent on a `CopyOnWriteArrayList`, on a snapshot iterator modified during iteration, and on an `ArrayList` whose mutations all ran under one **declared** lock | since #292 the collection's own type is consulted; since the shared `Lockset` it also intersects declared and agent-observed locks. An undeclared lock is still invisible, by design |
| ResourceLeakDetector | fires (two opens, no close) | silent (every open closed, nothing open at analysis time) | genuine both-direction detector |
| InterruptMonitor | fires (InterruptedException caught, flag never restored) | silent (catch-and-restore) | genuine both-direction detector |
| UncaughtExceptionHandlerDetector | fires (thread throws with no custom handler) | silent (same throw, handler installed) | genuine both-direction detector |
| CompletableFutureCompletionLeakDetector | fires (future created, never completed) | silent (created and completed) | genuine both-direction detector |
| ThreadLeakDetector | fires (thread started, still alive at analysis) | silent (joined and recorded as ended) | genuine both-direction detector; auto mode, which watches the global thread count, is off by default |
| ConstructorSafetyValidator | fires (another thread reads a field before the constructor returns) | silent (ordinary constructor, read after it returned) | genuine both-direction detector since #357 removed the sub-microsecond rule, which fired on every fast constructor |
| ThreadLocalMonitor | fires (set on two threads, never removed) | silent (`remove()` in a finally block) | genuine both-direction detector |
| LockDowngradeDetector | fires (write released before the read lock was taken, **and** another thread observed taking the write lock in the gap) | silent on the correct downgrade however contended, and silent on the same shape with nobody in the gap | evidence-gated since #355: the shape alone is also correct code that writes one thing and later reads another, so it is not reported without an observed writer. Deliberate false negative |

Every buggy variant above fires. The twin column is the interesting one, and the twins that
still fire on correct code now share a single cause: the guard is a lock nothing told the library
about. Two things make a lock knowable - the tracked instance's own monitor, which
`Thread.holdsLock` answers for without help, and a lock declared through
`AsyncTestContext.holdingLock(...)`. Under the agent with `fields=true` a third arrives on its
own, because the weaver instruments `MONITORENTER` and `MONITOREXIT`. Anything else is
unobservable: a `synchronized` block on a third object emits no callback, and a `synchronized`
method does not even carry a monitor instruction to weave. `DetectorAccuracyEvalTest` is the
authority on which row is which - each outcome above is one assertion in it.

## What this means for a user

- Findings from DeadlockDetector, LockOrderValidator and AtomicNonAtomicUpdateDetector
  are trustworthy in both directions: a finding means something is wrong, silence on
  these patterns means the specific bug shape is absent.
- For RaceConditionDetector, SharedMessageDigestDetector and SharedStatefulCryptoDetector
  a finding now means "touched by more than one thread, and no single lock covered every
  access". Code guarded by the instance's own monitor does not fire, and neither does code
  guarded by any other lock the test declares with `AsyncTestContext.holdingLock(...)`. A lock
  that was never declared is invisible and the finding stands, so it remains a prompt to verify
  synchronization rather than a verdict; the report wording says exactly that.
- For `AtomicityValidator` the answer depends on how the access was recorded.
  `recordFieldAccessOn(owner, field, value, isWrite)` gives it the full lockset, and a field
  covered by one lock across every access produces no finding. The agent-fed path gets a weaker
  model: it compares whole lock sets by fingerprint rather than intersecting them, so a field one
  thread holds `{A, B}` for and another holds `{A}` for is reported even though `A` protects it.
  The original overloads, which carry no lock information at all, keep their old meaning: "more
  than one thread touched this field and at least one wrote". The report only mentions locks when
  the caller supplied some.
- The rest of the Shared* family no longer has that limit; see the section below.
- `failOn = CRITICAL` gates on the trustworthy end of the scale.
  `failOn = HIGH` will fail builds over correct-but-shared code unless those findings
  are baselined; see the baseline mechanism in `ConcurrencyRunner`.

## The Shared* family (2026-08-14, extended 2026-08-20)

_Enforced by `SharedTypeAccuracyEvalTest`._ The same pair harness applied to all 19
detectors that watch a non-thread-safe JDK type, which is the largest cluster in the
catalogue and the one that carried most of the false-positive surface above.

| Direction | Result 2026-08-14 | Result now |
|---|---|---|
| Unguarded sharing (true positive) | 19 of 19 fire | 19 of 19 fire |
| `synchronized(instance)` twin (true negative) | 2 of 19 stay silent | 17 of 19 stay silent |
| Declared `ReentrantLock` twin (true negative) | not measured | 17 of 19 stay silent |
| Two threads, two different declared locks | not measured | 17 of 17 fire, correctly |

The 17 all reach those answers through one shared model rather than 17 copies of it.
`SelfGuard.TrackedInstance` keeps the Eraser candidate set - the locks held at every access to
that instance, intersected - and a detector's state class extends it, its record path calls
`noteAccess(instance)`, and its `analyze()` reports only when `sawUnguardedAccess()`, which is
now "the intersection is empty". The instance's own monitor is one member of that set rather
than a special case. The finding's wording comes from the same place (`SelfGuard.REPORT_NOTE`),
so the report cannot claim awareness the code does not have.

The last row is why the model is an intersection and not a per-thread "was anything held" flag.
Two threads that each take their own lock have serialised nothing, and a flag would call that
guarded; the intersection empties and the finding stands.

The two that keep firing are `SharedRandomDetector` and `SharedSecureRandomDetector`, and for
them that is the right answer rather than an unfinished one. `Random` and `SecureRandom` are both
thread-safe; those detectors report contention on a single instance, not corruption of it, and
`synchronized (instance)` does not falsify that - it serializes the callers a second time, on top
of the type's own internal synchronization, which makes the contention worse. They are pinned in
`CONTENTION_NOTE_BY_DESIGN`, and a probe that silenced one of them would fail the test.

Both sets are ratchets. A detector leaving `GUARD_ON_SELF_AWARE` is a regression; a new Shared*
detector in neither set fails the test until somebody decides which it is.

### The true-positive column was not free

Two detectors failed it when it was first run. `SharedRandomDetector` and
`SimpleDateFormatDetector` auto-registered per-instance state with
`s = map.get(id); if (s == null) { s = new State(); map.put(id, s); }`. Two threads
touching an instance for the first time both saw `null`, both built a state, and the
second `put` discarded the first - so each thread accumulated into its own object, the
surviving state recorded one thread, and the "more than one thread" test never tripped.
Both detectors went silent under exactly the contention they exist to find, while their
single-threaded unit tests stayed green throughout.

A sweep for the same shape found it in three more places: `CacheConcurrencyDetector`
(both record paths) and `LockLeakDetector.recordLockReleased` - the latter inverted, a
dropped release leaves acquires above releases and *invents* a leak in correct code.
`LockLeakDetector.recordLockAcquired` had already been fixed, with a comment describing
this exact hazard; the release path was missed. All five are now `computeIfAbsent`, and
`DetectorRegistrationRaceTest` pins the property that made them findable: a detector's
verdict must not change depending on whether two threads raced to register the instance.

## Known limits of this eval

- Recording-level: it measures the analyzers, not end-to-end reachability under a bare
  `@AsyncTest` (that is `DetectionCoverageTest`'s job) and not the agent's weaving
  (that is `AgentFeedsDetectorEndToEndTest`'s job).
- 25 distinct detectors of 142: the 9 above plus the 19 of the Shared* family, three of which
  appear in both. The first set was chosen to cover each mechanism class - access-pattern
  analyzers, per-thread state machines, graph analysis, and JVM introspection - and the second
  covers one whole cluster. Extending the pair harness further is mechanical; the helper
  (`onTwoThreads`) and the pinning convention are in place.
- The synchronization model is a lockset, not a happens-before relation. It covers the tracked
  object's own monitor, any lock declared with `AsyncTestContext.holdingLock(...)`, and, under
  the agent with `fields=true`, monitors taken by `synchronized` blocks in woven code. It does
  not cover an undeclared lock in unwoven code, a `synchronized` method (which carries the
  `ACC_SYNCHRONIZED` flag and no monitor instruction to weave), or ordering established by
  anything other than mutual exclusion - a `CountDownLatch`, a `volatile` handoff, or a queue all
  still read as unguarded. Ordering-based reasoning remains the roadmap's happens-before gap.
- No schedule-dependence measurement: these detectors are structural given their
  recordings, so run-to-run variance was not the question. The timing-sensitive
  detectors (livelock, starvation, high-contention) have a documented false-positive
  history under CI load (see TROUBLESHOOTING.md) and were not part of this pass.

## If a pinned false positive stops reproducing

That means a detector gained synchronization awareness. Flip the corresponding
assertion in `DetectorAccuracyEvalTest` from `assertTrue` to `assertFalse`, move the
detector's row up in the table above, and say so in the changelog. The eval is written
so that improvement shows up as a red test, exactly like a regression would.

That is exactly what happened on 2026-08-11: the `synchronized(digest)` twin went silent
when guard-on-self awareness landed, its assertion now pins the true negative, and new
external-lock twins pin what is still a false positive.

And again on 2026-08-28, with a correction worth keeping. The four "pinned false
positives" read as four defects and are not: all four are one documented limit, that an
**undeclared** lock is invisible and must therefore still be reported. Three already had a
declared-lock counterpart pinning the silence. One of those three,
`SharedMessageDigestDetector`, had the capability with no test naming it -
`SelfGuard.TrackedInstance.noteAccess` has intersected declared locks since the
guard-on-self probe grew into a lockset, so a refactor could have removed it without a
single assertion going red. It has a test now.

`ConcurrentModificationDetector` was the one real gap: no reference to `HeldLocks` or
`SelfGuard` anywhere in it, so a declared lock bought nothing. It now notes the held
lockset per mutation through the shared `Lockset`, extracted for this out of
`AtomicityValidator`, where it had been a private class nested two levels deep. The
extraction was verified behaviour-preserving before any behaviour was added: the whole
eval and `AtomicityValidatorTest` passed unchanged first.

The undeclared-lock rows stay `assertTrue`, and should. The limits section above already
says why: without the agent there is no monitor instruction to observe, so silence there
would be silence bought by nothing.
