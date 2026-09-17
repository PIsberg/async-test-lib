# corpus-eval: what is not gated yet

Companion to [corpus-eval.md](corpus-eval.md), which records what the module measures. This one
records what it does *not*, so that the next pass starts from a list rather than from a re-reading.

Everything here was found by one sweep on 2026-09-07, by running all four lanes with a probe that
dumped every finding rather than only the one each row names. Three items from that sweep were
closed in the same change, and are described in [corpus-eval.md](corpus-eval.md):

- a collateral-silence gate on the pair lanes, at the VERDICT tier lane one already uses;
- `CorpusGatesTest`, which shows each gate failing on input it must reject;
- a pairing-symmetry check, which holds the README's "add subjects in pairs" rule to being true.

Item 1 of what follows was closed on 2026-09-08: the recording lane's collateral bar is now
absolute, so its 119 silent rows are asserted to draw nothing from any of the 146 detectors at any
tier, while the agent-pair lane keeps the VERDICT/HIGH bar its `AgentRowPremise` scaffolding
requires. The asymmetry is reasoned about on `CorpusLane.failsOnAnyCollateral()` and recorded under
"What the gates could not catch" in [corpus-eval.md](corpus-eval.md). The numbering below is left
as it was so that references to an item still resolve.

Item 5 was closed the same day. `corpus-eval/README.md` said 45 classes where the corpus holds 82
subjects over 76, three lanes where there are four, seven libraries where the module puts eight on
a classpath, and two agent-fed detectors where `DetectorFeeds` names 18. Four sources divided by a
roster of 142 that has since grown to 146, and two counted VERDICT evidence at eight and nine where
the file holds 55. All are corrected, and `CorpusClaimsInDocsTest` now derives every one of them:
two tests for the module README, and one that reads the module's own sources and fails on any
sentence dividing by a roster the library no longer ships.

Item 4 was closed on 2026-09-16: all five gates now have failing-direction tests in `CorpusGatesTest`.
The gates (`everySubjectIsExercised`, `everySilentRowReachesItsDetector`, `everyCorpusBackedVerdictResolvesToItsPair`,
`noAgentRowRecordedItsOwnFinding`, and `everyPairedDetectorIsExposed`) were parameterized to accept test suites,
source, and subject lists, with the existing no-arg variants delegating to the static corpus defaults.

What follows is the remainder, in the order worth doing them.

## 2. Nothing checks that `CorpusGatesTest` still bites (Closed 2026-09-16)

Closed on 2026-09-16. `CorpusGatesTest` covers all gate methods in `CorpusGates` in both failing and accepting directions with synthetic inputs, verifying that any mutation or softening of gating logic (outcome, severity, blank diagnostics, missing methods, unexcluded bytecode, or unexercised pairs) trips a test failure.

## 2b. Pairs held back by a rule rather than a reading

0 PROMPT pairs are held back by a rule, not by a reading. `PairEvidence.unreviewed()` derives
that number from the rows, and `EveryEligiblePairIsPromotedOrExplainedTest` fails when this
sentence stops agreeing with it. When this section was first written it said 69, counted by hand
over every tier on 2026-09-07, and it went on saying so after the number had moved.

The rule holds a pair back when its two halves call different detector methods, which is a proxy
for "varies the defect and nothing else" rather than the thing itself. A pair leaves the backlog by
being read against both bodies and the detector, and the reading ends in one of two places: an
entry in `PairEvidence.REVIEWED_DESPITE_SHAPE`, after which the pair must be promoted, or an entry
in `PairEvidence.HELD_ON_MODEL` naming the model property that decides it. Four more pairs are held
back for naming two classes, which is the older rule and a rewrite rather than a reading.

**The first reading, 2026-09-14, and what it found.** Eleven pairs were read. Two were promoted and
nine were held on their detector's model:

- The two promotions, `NOTIFY_WITHOUT_MONITOR` and `SHARED_CHARSET_CODER`, were never different in
  shape. The rule was misreading the source: it counted `CorpusRecorder.recordCrash`, which is the
  harness keeping an exception, as a detector call, and it read `synchronized (monitor)` as a call
  to a helper named `synchronized`, whose body was then the first synchronized block anywhere in
  the lane. `PairEvidenceCallShapeTest` pins both, red before the fix. The second defect was shared
  with `SilentRowPremise`, which could have let a silent row pass on a neighbour's detector calls;
  both now read the source through one `LaneSource`.
- This section used to call `STREAM_CLOSING`, `EXECUTOR_SHUTDOWN` and `FUTURE_IGNORED` "certainly
  sound", because each pairs a body that omits the correct call against one that makes it. The
  reading found all three unsound, and not because of the pair. Each detector fires on correct code
  that reaches the same end another way (a stream closed in teardown, a try-with-resources
  executor, a `whenComplete` handler), so a finding does not mean the code is wrong. The missing
  call is the defect in the corpus; it is not the only thing the detector reports.
- `RACE_CONDITIONS`, `READ_WRITE_LOCK_FAIRNESS`, `THREAD_LOCAL_LEAKS`, `SCHEDULED_EXECUTOR`,
  `TIMER` and `LOCK_UPGRADE_DEADLOCK` are held for the same kind of reason, and
  `FILE_CHANNEL_POSITION_RACE`, whose argument was already in `verdict-evidence-corpus`, is now
  recorded there too. `READ_WRITE_LOCK_FAIRNESS` has since moved to `ADVISORY` (#569), which is not a
  promotion candidate, so its hold was removed.

Several of those reasons are detector defects rather than limits, and each has an issue. The
first to be fixed was `LOCK_UPGRADE_DEADLOCK` (#566): it now asks the lock whenever the recording
thread holds it, both corpus bodies take the real lock, and the pair was promoted.

**The second reading, 2026-09-14 (#571).** Eight more were read and all eight held:
`CONDITION_VARIABLES`, `CYCLIC_BARRIER`, `EXCHANGER`, `MISSED_SIGNAL`, `PHASER`, `STAMPED_LOCK`,
`REENTRANT_LOCK` and `WAKEUP_ISSUES`. They share one shape, which is worth naming because it
predicts the rest of the backlog: the body calls a method whose name is the finding
(`recordTimeout`, `recordBroken`, `recordTermination`, `recordStampNotReleased`, a
`wasNotified` flag), the detector adds it to a set, and nothing asks the `Exchanger`, the barrier or
the lock whether it happened. `LOCK_UPGRADE_DEADLOCK` and `NOTIFY_WITHOUT_MONITOR` are the
counterexamples, promoted because they ask the real object. The reading also turned up defects
beyond the model, dead state and a javadoc example that is itself correct code among them, filed
per detector.

Worth doing in small batches, and worth resisting the urge to clear it in one pass: the rule held
back two pairs that were sound, the three this section was surest of were not, and only reading
found either.

**The third reading, 2026-09-16 (#571).** The remaining twenty-one were read and all twenty-one held:
`ASYNC_PIPELINE`, `COMPLETABLEFUTURE_CHAIN`, `CONSTRUCTOR_SAFETY`, `COPY_ON_WRITE_COLLECTIONS`,
`FINAL_FIELD_MUTATION`, `FLOW_PUBLISHER_CONCURRENCY`, `FORK_JOIN_POOL`, `HTTP_CLIENT`,
`INHERITABLE_THREAD_LOCAL`, `LAZY_CONSTANT_MISUSE`, `LOCK_CONTENTION`, `PARALLEL_STREAMS`,
`SCOPED_VALUE`, `STABLE_VALUE_MISUSE`, `STRUCTURED_CONCURRENCY`, `STRUCTURED_TASK_SCOPE_MISUSE`,
`SYNCHRONIZERS`, `THREAD_POOL`, `VIRTUAL_THREAD_CONTEXT_LEAKS`, `WAIT_TIMEOUT`, and
`WEAK_REFERENCE_RACE`. They confirm the pattern identified in the second reading: in each case, the
detector is driven either by caller-asserted record methods (`recordForkWithoutJoin`, `recordMutation`,
`recordNullDereference`, `recordInfiniteWait`, `recordTaskRejected`, etc.) without bytecode or object
introspection, by synthetic string IDs rather than actual JVM construct instances (e.g.
`StructuredTaskScope`, `ScopedValue`, `StableValue`), or by arbitrary contention/ratio thresholds on
types that are thread-safe by specification (e.g. `CopyOnWriteArrayList`, lock contention ratio). None
inspects real JVM objects or synchronization primitives, so each requires agent instrumentation or
bytecode analysis before its pair can be evaluated for promotion. The unreviewed PROMPT backlog
held by call shape is now 0.

## 3. Severity is not pinned on a firing row (Closed 2026-09-16, expanded 2026-09-17)

Closed on 2026-09-16, expanded on 2026-09-17. `RecordingSubject` now records an optional `expectedSeverity` with `resolvedSeverity()` derived from the detector's model (`DetectorDefaultSeverity`). `CorpusGates.everySubjectGotTheOutcomeItsRecordedCallsOblige` verifies that finding severity matches expected severity when specified. `DetectorEffectivenessAndCorrectnessTest` validates that all 118 firing rows in the recording lane and all 34 firing rows in the agent-pair lane resolve to valid, non-degraded severity tiers.

## 4. Gates have no failing-direction test (Closed 2026-09-16, completed 2026-09-17)

Closed on 2026-09-16, completed on 2026-09-17. `CorpusGates` exposes parameterized overloads for all gates whose input can be synthesized (including `everySubjectIsExercised`, `everyRecordingSubjectIsExercised`, `everySilentRowReachesItsDetector`, `everyCorpusBackedVerdictResolvesToItsPair`, `noAgentRowRecordedItsOwnFinding`, `everyPairedDetectorIsExposed`, `checkLibraryExclusionLane`, `everyFindingIsAttributed`, `everyRecordingFindingIsAttributed`, and `everyReportingDetectorWasExposed`), as well as lane premise gates (`theDeadlockRowsRanInOrder`, `thePooledRowsPremiseHeld`, `theIllegalNotifyReallyThrew`). Finding diagnostics strictly require non-null severity, non-blank message, and non-null, non-blank evidence descriptions. `CorpusGatesTest` covers all 18 gates, diagnostics, and premises in `CorpusGates` in both failing and accepting directions. `DetectorEffectivenessAndCorrectnessTest` validates recording twin symmetry, trust tier mappings, and finding diagnostic requirements.

## 6. The refusal list is reviewed by nothing but a build (Closed 2026-09-16)

Closed on 2026-09-16. `DetectorRefusalThresholdsTest` pins the exact thresholds, experimental flags, and model assumptions cited across all fifteen entries in `DetectorCoverage.refused()`: the 100-access threshold and experimental property of `FALSE_SHARING`, the 1000ms threshold of `THREAD_STARVATION`, the 200ms probe deadline of `PLATFORM_THREAD_PER_TASK`, the 50ms segment threshold of `VIRTUAL_THREAD_CPU_BOUND`, the `availableProcessors` carrier count of `VIRTUAL_THREAD_CARRIER_EXHAUSTION`, the registry deferral of `LOCK_DOWNGRADE`, the virtual-thread inertia of `LIVELOCKS`, the adjacent-log requirement of `MEMORY_ORDERING`, and the no-innocent-twin rationale for all seven single-direction detectors.
