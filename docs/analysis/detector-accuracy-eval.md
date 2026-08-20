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

## What was measured

For each detector: does it fire on genuinely buggy concurrent code (true positive), and
does it stay silent on the correctly synchronized twin of that same code (true negative)?
The twin records the identical event stream through the detector's public recording API
while the underlying code holds a real lock, uses CAS, or orders its locks consistently.
Every recording happens from two live threads released by a CyclicBarrier.

This is a recording-level eval of 8 detectors plus one gated detector, not a corpus study
of all 142. It measures the analyzers' models, which is the property that decides whether
a finding on your code means your code is wrong. Since the guard-on-self change the twins
distinguish where the lock lives: guarding with the shared instance's own monitor
(`synchronized (theInstance)`) is now recognized, guarding with any other lock object is
still invisible.

## Results

| Detector | Buggy variant | Synchronized twin | Verdict |
|---|---|---|---|
| RaceConditionDetector | fires | silent when guarded by the shared object's own monitor; **fires** with an external lock | guard-on-self recognized; other locks invisible |
| AtomicityValidator | fires | **fires** | FP on correct code: the recording API carries only (field, value, isWrite) with no object reference, so there is nothing to probe a lock on; agent-fed, so the FP surface is the largest |
| SharedMessageDigestDetector | fires | silent with `synchronized(digest)`; **fires** with an external lock | guard-on-self recognized; other locks invisible |
| SharedStatefulCryptoDetector | fires | silent with `synchronized(mac)` | guard-on-self recognized; the external-lock direction is not yet pinned for this one |
| SharedSecureRandomDetector | n/a (sharing is the documented-safe idiom) | fires at MEDIUM | contention note by design, no longer a HIGH "corruption" claim |
| LockOrderValidator | fires (inversion, no deadlock needed) | silent | genuine both-direction detector |
| AtomicNonAtomicUpdateDetector | fires (get-then-set) | silent (CAS) | genuine both-direction detector |
| DeadlockDetector | fires (real deadlock, zero config; pinned by `DetectionCoverageTest`) | silent | genuine both-direction detector, near-zero FP |
| FalseSharingDetector | silent by default (experimental gate) | silent | findings uncorrelated with the phenomenon; opt-in via `-Dasync-test.experimental.false-sharing=true` |

Counting the measured variants: 7 of 7 buggy variants fire; 6 of 9 synchronized twins
stay silent. The three twins that still fire on correct code share one cause: the guard
is a lock object other than the shared instance itself. The recording paths now probe
`Thread.holdsLock` on the tracked instance, so the `synchronized (theInstance)` idiom is
recognized as guarded; a private lock object, a `ReentrantLock`, or any other external
guard remains invisible, and `AtomicityValidator`'s recording API carries no object
reference to probe at all.

## What this means for a user

- Findings from DeadlockDetector, LockOrderValidator and AtomicNonAtomicUpdateDetector
  are trustworthy in both directions: a finding means something is wrong, silence on
  these patterns means the specific bug shape is absent.
- For RaceConditionDetector, SharedMessageDigestDetector and SharedStatefulCryptoDetector
  a finding now means "touched by more than one thread, and at least one access held no
  lock on the instance itself". Code guarded by the instance's own monitor no longer
  fires. Code guarded by an external lock object still does, so a finding remains a
  prompt to verify synchronization rather than a verdict; the report wording says which
  locks are observed.
- Findings from `AtomicityValidator` still mean only "this field was touched by more than
  one thread", because its recording API carries no object reference to probe a lock on.
  The rest of the Shared* family no longer has that limit; see the section below.
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

The 17 all reach that answer through one shared probe rather than 17 copies of it.
`SelfGuard.TrackedInstance` holds the flag and the `Thread.holdsLock` call; a detector's state
class extends it, its record path calls `noteAccess(instance)`, and its `analyze()` reports only
when `sawUnguardedAccess()`. The finding's wording comes from the same place
(`SelfGuard.REPORT_NOTE`), so the report cannot claim awareness the code does not have.

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
- 24 distinct detectors of 142: the 8 above plus the 19 of the Shared* family, three of which
  appear in both. The first set was chosen to cover each mechanism class - access-pattern
  analyzers, per-thread state machines, graph analysis, and JVM introspection - and the second
  covers one whole cluster. Extending the pair harness further is mechanical; the helper
  (`onTwoThreads`) and the pinning convention are in place.
- The synchronization model is the tracked object's own monitor only, probed with
  `Thread.holdsLock` on the accessing thread at record time. External lock objects,
  `java.util.concurrent` locks, and locks observed by other threads are all invisible;
  general lock awareness remains the roadmap's happens-before gap.
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
