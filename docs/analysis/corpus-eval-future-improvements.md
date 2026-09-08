# corpus-eval: what is not gated yet

Companion to [corpus-eval.md](corpus-eval.md), which records what the module measures. This one
records what it does *not*, so that the next pass starts from a list rather than from a re-reading.

Everything here was found by one sweep on 2026-09-07, by running all four lanes with a probe that
dumped every finding rather than only the one each row names. Three items from that sweep were
closed in the same change, and are described in [corpus-eval.md](corpus-eval.md):

- a collateral-silence gate on the pair lanes, at the VERDICT tier lane one already uses;
- `CorpusGatesTest`, which shows each gate failing on input it must reject;
- a pairing-symmetry check, which holds the README's "add subjects in pairs" rule to being true.

What follows is the remainder, in the order worth doing them.

## 1. Sub-VERDICT collateral on a silent row is printed, not gated

`noCollateralFindingOnASilentRow` fails a run only at VERDICT/HIGH or VERDICT/CRITICAL, matching
`CorpusReport.isFalsePositive`. Everything below that is listed in the report's "Collateral
findings on silent rows" section and asserted by nothing, which is the right call for the one entry
that exists today - `SleepInLockDetector` on the deadlock pair's silent half, where the sleep is
required by `AgentRowPremise` and the finding is true. It is the wrong call as a permanent
arrangement: the section is a list nobody is obliged to read, which is where the collateral set
already spent its life before this change.

The recording lane could take the absolute version today at no cost. All 118 of its silent rows are
silent across the whole roster at every tier, measured 2026-09-07, so a per-lane bar - absolute in
the recording lane, VERDICT in the agent-pair lane - would ratchet 118 rows to their strongest form
and leave the one genuine exception where it belongs. It was not done in the same change because a
gate that means two different things in two lanes needs the asymmetry written down where the next
reader of either lane will find it, and that is a decision about how the module explains itself
rather than a line of code.

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

## 2b. Sixty-nine pairs are held back by a rule, not by a reading

The 2026-09-07 promotion wave registered 47 of the 116 same-class pairs. The remaining 69 are held
back because the two halves call different detector methods, which is a proxy for "varies the
defect and nothing else" rather than the thing itself. Some of them are certainly sound:
`RESOURCE_LEAKS`, `STREAM_CLOSING`, `EXECUTOR_SHUTDOWN`, `FUTURE_IGNORED` and
`ATOMIC_NON_ATOMIC_UPDATE` all pair a body that omits the call that makes it correct against one
that makes it, so the missing call is the defect.

Each needs a reading of both bodies, and the outcome is either a named entry in
`PairEvidence.REVIEWED_DESPITE_SHAPE` with the reason, or a rewritten pair. That is roughly an hour
of careful work per handful and cannot be batched, which is why the wave stopped where the rule
stops rather than where a reviewer's patience does. Four more are held back for naming two classes,
which is the older rule and a rewrite rather than a reading.

Worth doing in small batches, and worth resisting the urge to clear it in one pass: the wave that
created this document's parent found the `EXCHANGER` pair wrong on a rationale no rule could read,
and the same will be true of some of these 69.

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

## 5. The module's own prose is not checked

`CorpusClaimsInDocsTest` pins the counts in the root `README.md` and in
[corpus-eval.md](corpus-eval.md). It does not read `corpus-eval/README.md`, and that README has
drifted:

- "45 third-party classes", where the corpus holds 82 subjects over 68 distinct classes;
- "A run executes three lanes", and a lane table with three rows, where there are four. The
  `agent-pairs` lane is missing from both.

Two counts inside `CorpusGates` itself have drifted the same way: "137 of the 142 detectors" where
the roster is 146, and "Eight detectors are classified VERDICT" where
`META-INF/async-test/verdict-evidence-corpus` now has eleven lines. `SilentRowPremise` says "nine
silent rows" for the same eleven.

Extending `CorpusClaimsInDocsTest` to the module README is a few lines. The counts inside javadoc
are harder to gate without the "regex over every integer" failure the existing test's javadoc warns
against, and are probably better served by deriving the sentence than by checking it.

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
