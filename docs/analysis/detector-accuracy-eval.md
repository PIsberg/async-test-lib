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

_Updated 2026-09-26 (evidence caps): a both-directions case is necessary for VERDICT and no longer
sufficient. The detector must also decide from the JVM's own state or from synchronization it can
see, because a detector whose finding is the recorded call itself passes both directions by
construction: record the defect and it fires, leave it out and it is silent. Six of the in-repo
VERDICT pairs belong to detectors that decide that way or on a threshold, among them the
`LockLeakDetector` and `CompletableFutureExceptionDetector` promotions above, and those detectors
are now FACT or PROMPT. Their cases still run and still pass, and they show what a FACT or PROMPT
needs: that the detector separates the recorded bug from the recorded fix._

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
| ReentrantLockDetector | fires (a hold re-entered and never released, recorded acquire and release balanced) | silent (the same contention, a `tryLock` timeout recorded and handled, the lock free at analysis) | evidence-gated since #589: the lock's own `isLocked()` at analysis is the finding, and a recorded timeout is context, because backing off on one is correct |
| CompletableFutureExceptionDetector | fires (completed exceptionally with no handler registered) | silent (same failure, handler registered first) | genuine both-direction detector |
| ConcurrentModificationDetector | fires (modification recorded while an iterator is live; iteration outside the lock the writers hold) | silent on a `CopyOnWriteArrayList`, on a snapshot iterator modified during iteration, on an `ArrayList` whose mutations all ran under one **declared** lock, and on concurrent iteration plus mutation all under `synchronized (list)` | since #292 the collection's own type is consulted; since the shared `Lockset` it also intersects declared and agent-observed locks. The concurrent-iteration finding had no lock check until 2026-09-25 and reported the `synchronizedList` iteration idiom; it now intersects the locks held at every iteration start and mutation. An undeclared lock is still invisible, by design |
| ResourceLeakDetector | fires (two opens, no close) | silent (every open closed, nothing open at analysis time) | genuine both-direction detector |
| InterruptMonitor | fires (InterruptedException caught, flag never restored) | silent (catch-and-restore) | genuine both-direction detector |
| UncaughtExceptionHandlerDetector | fires (thread throws with no custom handler) | silent (same throw, handler installed) | genuine both-direction detector |
| CompletableFutureCompletionLeakDetector | fires (future created, never completed) | silent (created and completed) | genuine both-direction detector |
| ThreadLeakDetector | fires (thread started, still alive at analysis) | silent (joined and recorded as ended) | genuine both-direction detector; auto mode, which watches the global thread count, is off by default |
| ConstructorSafetyValidator | fires (another thread reads a field before the constructor returns) | silent (ordinary constructor, read after it returned) | genuine both-direction detector since #357 removed the sub-microsecond rule, which fired on every fast constructor; since 1.12.3 the records are checked against the stack, so a read after the constructor returned is silent even when the end was recorded late or never, including on a pooled thread that has since recorded the start of another instance (#778) |
| ThreadLocalMonitor | fires (set on two threads, never removed) | silent (`remove()` in a finally block) | genuine both-direction detector |
| LockDowngradeDetector | fires (write released before the read lock was taken, **and** another thread observed taking the write lock in the gap) | silent on the correct downgrade however contended, and silent on the same shape with nobody in the gap | evidence-gated since #355: the shape alone is also correct code that writes one thing and later reads another, so it is not reported without an observed writer. Deliberate false negative |
| StampedLockDetector | fires (a failed `validate()` with no fallback; a write stamp one thread took and never released; a read stamp released again while another reader holds) | silent (the `validate()`-then-`readLock()` fallback; the same write released in a `finally`; the same read released once) | leaks since #588 need two facts: an acquisition no recorded unlock matched, and the lock still held at analysis; matching is per thread and lock instance, not per name; since #604 a repeated read release is reported only when the lock's reader count confirms a hold was taken |
| CountDownLatchDetector | fires (a worker never signals, the waiter's await expires) | silent when a later await on the same latch succeeded | evidence-gated since #477: a CountDownLatch only counts down and never blocks again once it is at zero, so a success proves the latch fell and the earlier timeout was a wait that started too early |
| ConditionVariableDetector | fires with the lock and predicate registered (the producer signals `notFull`, the consumer stays parked on `notEmpty` at analysis while the item is ready); registered without its lock, or with the lock but no predicate, the same parked consumer is only a note (pinned false negative, #666) | silent when the producer signals `notEmpty`, including a second signal made into an empty condition; silent on an idle consumer parked while its predicate is false; with the lock registered, silent on an await the body recorded but never parked in | since #666 the only finding is a thread `getWaitQueueLength` shows parked while the registered predicate holds; a missing signal (per-await pairing since #583), a recorded await with no lock, and a lock-only parked thread are notes. A worker interrupted at the round timeout has left the queue before analysis, so the lock cannot count it |
| PhaserDetector | fires (two workers `arriveAndDeregister` a phaser created for one party; the second gets a negative phase) | silent when the phaser is created for both workers and terminates at zero parties | decided on the real phaser since #587: a negative arrival phase counts only when no party is left registered, so termination, `forceTermination` and `onAdvance` are silent, and a timeout counts only if its phase is still current at analysis |
| WakeupDetector | fires (an `if`-guarded wait returns before anyone notifies and the consumer goes on) | silent when a `while` loop waits again after the same return, including a notify made with nobody waiting | re-check observed since #590: an unsignalled return is a finding only when the same thread does not wait again; a deadline loop that records its give-up with `recordGaveUp` is silent (#607), a timed `if` guard that proceeds still fires |
| TimerDetector | fires (a task falls due while another task still holds the timer's one thread) | silent on the same slow task with nothing falling due behind it, past the 100 ms it used to call long-running | observed since #575: a task's own `scheduledExecutionTime()` inside a recorded run of a different task, no duration threshold. Two tasks due at the same instant are a deliberate false negative |
| PhaserDetector (stall) | fires (a party returns early without arriving; the other stays parked in `arriveAndAwaitAdvance()`, no timeout recorded) | silent when the early-return path arrives in `finally` | since #602 an `arriveAndAwaitAdvance()` bracketed by `recordAwaitAdvanceStarted`/`recordAwaitAdvanceReturned` that never returned is a stall if its phase is still current, a party has not arrived, and its thread is still parked in `Phaser` at analysis |

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
  access". For the two Shared* detectors both halves are judged within one invocation round,
  because the runner finishes one round before it starts the next; see the Shared* section
  below. Code guarded by the instance's own monitor does not fire, and neither does code
  guarded by any other lock the test declares with `AsyncTestContext.holdingLock(...)`. A lock
  that was never declared is invisible and the finding stands, so it remains a prompt to verify
  synchronization rather than a verdict; the report wording says exactly that.
- For `AtomicityValidator` the answer depends on how the access was recorded.
  `recordFieldAccessOn(owner, field, value, isWrite)` gives it the full lockset, and a field
  covered by one lock across every access of a round produces no finding. Since 1.12.3 that
  lockset is judged per round on both paths, so a different lock in each round is consistent
  locking: the harness orders the rounds. The owner-aware path also groups accesses by the owner
  it names, so the same field of two objects that each stay on one thread is two histories, not
  one shared field (#750). The agent-fed path gets a weaker
  model: it compares whole lock sets by fingerprint rather than intersecting them, so a field one
  thread holds `{A, B}` for and another holds `{A}` for is reported even though `A` protects it.
  The original overloads, which carry no lock information at all, keep their old meaning: "more
  than one thread touched this field and at least one wrote". On the agent-fed path an object
  that changes hands through an observed take (a queue `poll`, an atomic `getAndSet`; out of an
  `ArrayDeque` or another unsynchronized `java.util` queue, not a poll whose recorded locks and the
  offer's are both non-empty and share none, #751) is judged per ownership generation, so each owner may bring its own lock, or none while the object is exclusive
  to it (#555); a lock that changes inside one round with no take, a thread that uses an object
  it did not take, and two locks inside one generation still fire, and each direction is a case in
  `DetectorAccuracyEvalTest`. The report only mentions locks when
  the caller supplied some.
- **Ownership-generation boundary (#559).** Exclusivity ends at the first foreign access in drain
  order, which the publication order can defeat: an alias kept from before a take whose only access
  is published after every taker access used to leave the taker's accesses exclusive and itself
  agreeing with nothing, so the race was silent. A foreign access inside the generation the receiver
  is still in now withdraws the taker's exclusivity for the whole generation, and
  `atomicityAliasWritingAfterTheTakersLastAccessStillFires` fires (it was silent before) while
  `atomicityTakesWithNoAliasStaySilent` stays silent. A generation a later take closed is judged
  too ([#630](https://github.com/PIsberg/async-test-lib/issues/630)): an access by a thread that was
  neither that generation's taker nor the previous generation's owner withdraws the exclusion, and
  `atomicityAliasInAGenerationALaterTakeClosedFires` pins it. The previous owner's access stays a
  hand-off whether it was published late (`atomicityLateAccessByPreviousOwnerInClosedGenerationStaysSilent`)
  or drained after the take (`atomicityPreviousOwnerAccessDrainedAfterTheTakeStaysSilent`). When
  the take is the first recorded event for a receiver, an offer names generation 0's owner: the
  queue hooks (`Queue.offer`, `Collection.add` on a `Queue`, `BlockingQueue.offer` in both forms and
  `put`) publish the element and the queue's identity before the queue accepts it, and the `poll`
  hooks publish the queue's identity with the take. An offer into the container the take came out
  of excuses only the offerer, so an alias write there fires
  (`atomicityAliasAfterAnObservedOfferAndATakeFirstFires`) while the offerer's late write stays
  silent (`atomicityOfferersLateWriteAfterATakeFirstStaysSilent`). Since
  [#664](https://github.com/PIsberg/async-test-lib/issues/664) the reference slots
  (`AtomicReference`, `AtomicReferenceFieldUpdater`, `AtomicReferenceArray`, an instance-field
  `VarHandle`) and JCTools `offer`/`poll` publish the same offer and take with their container;
  `OwnershipOfferWeavingTest` in the agent module pins both directions per shape through woven code.
  The boundary that remains is a take-first generation with no matching offer, where every
  foreign access is still excused, alias or not
  (`atomicityOfferToAnotherContainerKeepsTheTakeFirstExcuse`): an element that entered the
  queue through an unwoven method (`addAll`, `Deque.offerFirst`, `push`, or code outside the
  agent's `includes`), a `VarHandle` take from a static field or an array element, and an offer
  into a different container than the take names. The over-report #630 left open, an element
  offered by one thread, removed and put back by another through unwoven calls, cannot arise
  from `BlockingQueue.take` or `drainTo` any more: the take consumes the offer
  (`atomicityOfferConsumedByAnObservedTakeKeepsTheReAddersWriteSilent`, twin
  `atomicityAliasAfterAnObservedTakeAndPutBackFires`) and the drain drops every offer into the
  queue (`atomicityStaleOfferDroppedByADrainKeepsTheTakeFirstExcuse`, twins
  `atomicityOfferAfterADrainStillNamesTheOwner` and `atomicityDrainOfAnotherContainerKeepsTheOffer`).
  It can still arise when the removal is not reported as a take: `Queue.remove()`,
  `remove(Object)`, `removeIf`, or any removal in code outside `includes`.
  Only the most recent offer counts, so any woven offer in between clears it.
  Measured on corpus-eval lane one (local JDK 26), before #630 changed closed generations and not
  re-run since: the rule withdrew exclusivity in 7 analyses, all
  in netty's `adaptiveByteBufAllocator_bufferAndRelease`, and the documented-thread-safe column
  stayed at 0 of 100 against main.
- The rest of the Shared* family no longer has that limit; see the section below.
- `failOn = CRITICAL` gates on the trustworthy end of the scale.
  `failOn = HIGH` will fail builds over correct-but-shared code unless those findings
  are baselined; see the baseline mechanism in `ConcurrencyRunner`.

## The Shared* family (2026-08-14, extended 2026-08-20 and 2026-08-31)

_Enforced by `SharedTypeAccuracyEvalTest`._ The same pair harness applied to all 20
detectors that watch a non-thread-safe JDK type, which is the largest cluster in the
catalogue and the one that carried most of the false-positive surface above.
`WeakHashMapSharedDetector` joined the roster on 2026-08-31, when the corpus eval's
recording lane caught it flagging `synchronized (map)` - the external synchronization
`WeakHashMap`'s own javadoc asks for - as loudly as the unguarded bug; it carries the
lockset now and both directions are pinned like the rest.

| Direction | Result 2026-08-14 | Result now |
|---|---|---|
| Unguarded sharing (true positive) | 19 of 19 fire | 20 of 20 fire |
| `synchronized(instance)` twin (true negative) | 2 of 19 stay silent | 18 of 20 stay silent |
| Declared `ReentrantLock` twin (true negative) | not measured | 18 of 20 stay silent |
| One thread per round, a fresh thread each round (true negative) | not measured | 18 of 18 stay silent |
| Two threads, two different declared locks | not measured | 18 of 18 fire, correctly |

The 18 all reach those answers through one shared model rather than 18 copies of it.
`SelfGuard.TrackedInstance` keeps the Eraser candidate set - the locks held at every access to
that instance, intersected - and a detector's state class extends it, its record path calls
`noteAccess(instance)`, and its `analyze()` reports only when `sawUnguardedSharing()`: within
one invocation round, more than one thread touched the instance and the intersection of that
round's locksets is empty. The instance's own monitor is one member of that set rather than a
special case. The finding's wording comes from the same place (`SelfGuard.REPORT_NOTE`), so the
report cannot claim awareness the code does not have.

The round matters because the runner orders rounds: every worker of one round has finished
before the next round's are submitted, so two threads that used an instance in different rounds
never overlapped. Until 2026-09-25 both the thread count and the lockset spanned the whole run.
With pooled platform workers, sequential use by different workers in different rounds read as
sharing; with virtual threads, the default, every body execution runs on a fresh thread, so any
instance used in two rounds at all was "accessed from 2 threads". A lock that guarded all of one
round and a different lock that guarded all of the next also emptied the intersection. The round
comes from a clock `AsyncTestContext` binds to each worker (`SelfGuard.Scope`); a detector
driven with no context installed sees one round, the whole run, as before. The "one thread per
round" row pins it for the whole roster.

The verdict moved first; the thread sets a detector keeps beside it followed (#748). Reports kept
printing "accessed from N threads" over every thread of the run, and three detectors kept a
condition of their own over the run as well: `StringBuilderDetector`'s writers,
`SharedTimeZoneDetector`'s mutators and `SharedJsonMapperReconfigDetector`'s users. A round that
raced plus one writer in each of two other rounds read as two writers, and a mapper used by two
threads in one round made a lone reconfiguration in a later round a race. Those sets are now kept
per round (`SelfGuard.RoundThreads`): the extra condition must hold within one round, and a
report counts and names the threads of the round the finding came from, or of the busiest round
where no round is marked. Pinned in each detector's own test and in
`SharedMessageDigestDetectorTest` for the family's printed count. `StringBuilderDetector`'s
exception finding followed (#783): it counts the users of the busiest round an exception came
from, so one thread per round, each failing alone, is not concurrent access.

`FalseSharingDetector`, off unless its experimental property is set, followed in #765. Its pair
predicate (two or more threads on one field, a different set on the adjacent one) and its
high-contention line compared thread sets over the run. It keeps no per-instance verdict, and a
pair compares two fields, which the three rounds `RoundThreads` retains cannot answer, so the round
is stamped on each recorded access instead, from the same `SelfGuard.Scope` clock, and both
predicates are taken within one round. Pinned in `FalseSharingDetectorTest` in both directions.

Within a round the verdict is also per owner. A `MessageDigest` pool checked out through a
`BlockingQueue` (take, use, put back) gives each thread the digest alone, yet two threads touched
it in one round and no lock covered the use, so it read as sharing. A take is the hand-off edge:
the queue held the only shared reference, so the previous owner put it back before the next could
take it. `SelfGuard.Scope.ownershipTaken` counts takes per tracked instance, and the window key is
(round, takes so far), so one owner's accesses and the next's are judged apart. The agent's woven
queue takes and atomic-slot swaps (`collections=true`) reach it synchronously from
`TelemetryRegistry.ownershipTaken`, on the taking thread; a checkout the weaver never sees is
declared with `AsyncTestContext.ownershipTaken(instance)`. An old owner that keeps using the
instance after handing it back joins the new owner's window and is still reported. Pinned in
`SharedMessageDigestDetectorTest`, through the woven hook methods called directly rather than a
real agent attach. What it does not see: a pool of wrapper objects, where the take names the
wrapper and the access names the digest inside it.

Within an owner's window the verdict also follows the shared `HappensBefore` model (1.12.3). Two
threads in one round that the program ordered, one using a `MessageDigest` and counting a latch
down while the other awaits it and then uses the digest, or a parent that uses it, starts a child
that uses it and joins the child, never overlapped, yet read as sharing, because a lockset cannot
see an ordering no lock provides. Each access now carries its thread's clock, and a thread whose
access the model orders after the window's latest one takes the instance over. The edges come
from the agent's woven latches, semaphores, queue and map hand-offs, `Thread.start` and `join`, or
from `HappensBefore.release`/`acquire`/`fork`/`join` in the test. An edge only removes a finding:
two siblings started by one parent, and two threads that use the digest at once after a hand-off,
still fire, and an unwoven latch nobody declared orders nothing. Pinned in
`SharedMessageDigestDetectorTest`, through the manual API and through the hook methods the weaver
substitutes. A take-over also restarts the lockset (#746): one thread setting the digest up
unlocked and handing it to threads that always lock it is consistent locking, provided every later
access is ordered after the hand-off. A guarded use reached through an edge the model never saw is
not, and brings the unlocked set-up back into the lockset, so it still fires. `AtomicNonAtomicUpdateDetector`, whose finding needs no second thread, takes only the
per-round lockset from the same windows (`sawUnguardedRound()`), so one lock per round, a
different one each round, no longer reads as inconsistent locking.

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

**A fifth pinned false positive, found by measurement on 2026-08-28.**
`ConcurrentModificationDetector` decides whether mutation is safe from the collection's
*package name*: `java.util.concurrent.` or `java.util.Collections$Synchronized`. Every
correct third-party collection is therefore on the wrong side of that test. Measured in the
corpus module, two threads adding to each:

| Collection | Reports | Correct |
|---|---|---|
| `java.util.concurrent.CopyOnWriteArrayList` | silent | yes |
| `Collections.synchronizedList(...)` | silent | yes |
| guava `ConcurrentHashMultiset` | **fires** | **no** |
| commons-collections4 `SynchronizedCollection` | **fires** | **no** |
| `java.util.ArrayList` | fires | yes |

**Fixed the same day, and not the way the issue proposed.** A denylist would have closed it at
the cost of going silent on commons-collections4's documented-unsafe maps, which is a worse trade
for a detector whose job is finding exactly those. Java has no thread-safe-collection marker and
this detector's API is `Collection`-typed, so `ConcurrentMap` cannot be consulted either. What is
left is the class name, and the ecosystem uses it with near-total consistency: `Concurrent*`,
`CopyOnWrite*`, `Synchronized*` across the JDK, guava, commons and Spring. The model now reads
that convention instead of the package prefix.

Re-measured after the change, same subjects:

| Collection | Before | After | Correct |
|---|---|---|---|
| `CopyOnWriteArrayList`, `Collections.synchronizedList` | silent | silent | yes |
| guava `ConcurrentHashMultiset` | fires | **silent** | yes |
| commons-collections4 `SynchronizedCollection` | fires | **silent** | yes |
| `java.util.ArrayList` | fires | fires | yes |
| commons-collections4 `CursorableLinkedList` | fires | fires | yes |
| guava `ArrayListMultimap.values()` | fires | fires | yes |

A convention is not a proof, and the residue is pinned rather than papered over: a collection that
is thread-safe and says so nowhere in its name is still reported. That is what the
`ThreadSafeBag` row above holds, and it is the honest limit of a language with no marker for this.

The pinned test uses a local thread-safe collection rather than guava, so it measures the
model and not a dependency: `async-test-lib` does not carry the corpus libraries on its test
classpath, and the subject of the test is the package-name check, not any one library.
