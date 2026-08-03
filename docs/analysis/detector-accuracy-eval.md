# Detector-Accuracy Eval: Buggy Code vs the Synchronized Twin

_Branch: `fix/ga-eval-findings` - date: 2026-08-03 - enforced by
`DetectorAccuracyEvalTest` (async-test-lib test suite), so the table below cannot drift
from the code without a red build._

## What was measured

For each detector: does it fire on genuinely buggy concurrent code (true positive), and
does it stay silent on the correctly synchronized twin of that same code (true negative)?
The twin records the identical event stream through the detector's public recording API
while the underlying code holds a real lock, uses CAS, or orders its locks consistently.
Every recording happens from two live threads released by a CyclicBarrier.

This is a recording-level eval of 7 detectors plus one gated detector, not a corpus study
of all 127. It measures the analyzers' models, which is the property that decides whether
a finding on your code means your code is wrong.

## Results

| Detector | Buggy variant | Synchronized twin | Verdict |
|---|---|---|---|
| RaceConditionDetector | fires | **fires** | FP on correct code: no lock model |
| AtomicityValidator | fires | **fires** | FP on correct code: no lock model; agent-fed, so the FP surface is the largest |
| SharedMessageDigestDetector | fires | **fires** | FP on correct code: tracks threads, not locks; report now says "verify external synchronization" |
| SharedSecureRandomDetector | n/a (sharing is the documented-safe idiom) | fires at MEDIUM | contention note by design, no longer a HIGH "corruption" claim |
| LockOrderValidator | fires (inversion, no deadlock needed) | silent | genuine both-direction detector |
| AtomicNonAtomicUpdateDetector | fires (get-then-set) | silent (CAS) | genuine both-direction detector |
| DeadlockDetector | fires (real deadlock, zero config; pinned by `DetectionCoverageTest`) | silent | genuine both-direction detector, near-zero FP |
| FalseSharingDetector | silent by default (experimental gate) | silent | findings uncorrelated with the phenomenon; opt-in via `-Dasync-test.experimental.false-sharing=true` |

Counting the measured pairs: 6 of 6 buggy variants fire; 3 of 6 synchronized twins stay
silent. The three that fire on correct code share one cause: their input is
"(thread, access)" tuples with no representation of synchronization, so a lock-protected
event stream is indistinguishable from a racy one.

## What this means for a user

- Findings from DeadlockDetector, LockOrderValidator and AtomicNonAtomicUpdateDetector
  are trustworthy in both directions: a finding means something is wrong, silence on
  these patterns means the specific bug shape is absent.
- Findings from the shared-instance and access-pattern family (RaceConditionDetector,
  AtomicityValidator, the Shared* detectors) mean "this object was touched by more than
  one thread". That is a prompt to verify synchronization, not a verdict. Their report
  wording says so since this change.
- `failOn = CRITICAL` gates on the trustworthy end of the scale.
  `failOn = HIGH` will fail builds over correct-but-shared code unless those findings
  are baselined; see the baseline mechanism in `ConcurrencyRunner`.

## Known limits of this eval

- Recording-level: it measures the analyzers, not end-to-end reachability under a bare
  `@AsyncTest` (that is `DetectionCoverageTest`'s job) and not the agent's weaving
  (that is `AgentFeedsDetectorEndToEndTest`'s job).
- 7 of 127 detectors. The evaluated set was chosen to cover each mechanism class:
  access-pattern analyzers, per-thread state machines, graph analysis, and JVM
  introspection. Extending the pair harness to more detectors is mechanical; the
  helper (`onTwoThreads`) and the pinning convention are in place.
- No schedule-dependence measurement: these detectors are structural given their
  recordings, so run-to-run variance was not the question. The timing-sensitive
  detectors (livelock, starvation, high-contention) have a documented false-positive
  history under CI load (see TROUBLESHOOTING.md) and were not part of this pass.

## If a pinned false positive stops reproducing

That means a detector gained synchronization awareness. Flip the corresponding
assertion in `DetectorAccuracyEvalTest` from `assertTrue` to `assertFalse`, move the
detector's row up in the table above, and say so in the changelog. The eval is written
so that improvement shows up as a red test, exactly like a regression would.
