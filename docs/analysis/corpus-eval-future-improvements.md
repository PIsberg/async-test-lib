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

What follows is the remainder, in the order worth doing them.

## 2. Nothing checks that `CorpusGatesTest` still bites

The gate self-test asserts that a gate throws. If a gate stops throwing, the self-test goes red -
that direction was checked once, on 2026-09-07, by making
`noFalsePositiveOnDocumentedThreadSafeCode` return no false positives and confirming
`aVerdictFindingOnSafeCodeFailsTheFalsePositiveGate` failed with "Expected
org.opentest4j.AssertionFailedError to be thrown, but nothing was thrown". That check was manual
and is not repeated by anything.

The standing form of it is mutation testing scoped to `CorpusGates`, which the repository already
runs on the library. It is not obviously worth a two-hour job for five hundred lines of test-only
code, so it is recorded rather than proposed.

## 2b. Pairs held back by a rule rather than a reading

29 PROMPT pairs are held back by a rule, not by a reading. `PairEvidence.unreviewed()` derives
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
  recorded there too.

Several of those reasons are detector defects rather than limits, and each has an issue. The
first to be fixed was `LOCK_UPGRADE_DEADLOCK` (#566): it now asks the lock whenever the recording
thread holds it, both corpus bodies take the real lock, and the pair was promoted.

Worth doing in small batches, and worth resisting the urge to clear it in one pass: the rule held
back two pairs that were sound, the three this section was surest of were not, and only reading
found either.

## 3. Severity is not pinned on a firing row

`RecordingSubject` states an expectation and nothing else, and
`everySubjectGotTheOutcomeItsRecordedCallsOblige` asserts only that something fired. A finding that
degrades from `HIGH` to `LOW` changes which builds `failOn` stops and no corpus row notices.

Measured on the 117 `MUST_FIRE` rows of the recording lane, 2026-09-07:

| Severity | Rows |
|---|---:|
| CRITICAL | 14 |
| HIGH | 81 |
| MEDIUM | 21 |
| LOW | 1 |

Tier is deliberately excluded. It comes from `DetectorTrust.tierOfDetector` and is a library
constant with its own gate, so a corpus assertion on it would be a second copy of a fact that
already has an owner. Severity is per-violation, which is what makes it worth a row.

The cost is one field on `RecordingSubject` and 117 rows to fill in, and the risk is the
`DetectorCoverage` failure mode in reverse: a field that is filled in by copying whatever the run
printed states nothing. It is worth doing only if the value is written from the detector's model
rather than from the last report.

## 4. Five gates have no failing-direction test

`CorpusGatesTest` covers every gate whose input can be synthesised. Five remain, for two different
reasons, and both are recorded here rather than in a comment that only the next reader of that file
would find.

Four read only static module state, so the input that would fail them cannot be built without
mutating `Corpus` or a lane's source: `everySubjectIsExercised`,
`everySilentRowReachesItsDetector`, `everyCorpusBackedVerdictResolvesToItsPair` and
`noAgentRowRecordedItsOwnFinding`. Each does fail loudly when its own input goes missing, which is
the failure mode that actually threatens them.

`everyPairedDetectorIsExposed` is uncovered from the other side: no lane exists in which a paired
detector is unexposed, so there is no input that fails it. It is currently unfalsifiable rather
than untested.

The fix for both shapes is the same and is not small: let the gates take their corpus as an
argument instead of reading the static one, so a test can hand them a corpus built for the
occasion. That is a refactor of `CorpusGates`, `Corpus` and every caller, and it should be done
when something else already needs it.

## 6. The refusal list is reviewed by nothing but a build

Fifteen detectors are refused a pair in `DetectorCoverage`, each with a reason.
`EveryDetectorIsPairedOrRefusedTest` holds the list to being exhaustive and to containing nothing
already paired, which is the bookkeeping half. What no gate can check is whether a reason is still
true: eight of them turn on a threshold or a flag inside a detector, and a detector that grows a
seam for its threshold makes its refusal wrong without touching this module.

The sweep that reopened five refusals is recorded under "Reopening the refusals" in
[corpus-eval.md](corpus-eval.md), and it was a manual pass. A cheap approximation would be to pin
the thresholds each refusal cites, so that changing one fails here and the entry gets re-read; the
honest version is a periodic re-triage, which is a task rather than a gate.
