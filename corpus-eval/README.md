# corpus-eval

Measures what the 146 detectors report on 139 subjects drawn from 116 third-party classes with a
documented thread-safety contract, and how many of the 146 the run could feed at all. The write-up,
with the numbers and what they do and do not support, is
[docs/analysis/corpus-eval.md](../docs/analysis/corpus-eval.md).

## Why it is a standalone module

The nine corpus libraries are *subjects*, not tools. Keeping the module out of the reactor keeps
them off every classpath that matters: nothing in `async-test-lib`, the published artifacts or the
Gradle build resolves them, exactly as `consumer-fixture/` stays outside for its own reason.

## Running it

```bash
mvn install -DskipTests -Djacoco.skip=true    # so the module can resolve the current version
mvn -f corpus-eval/pom.xml test
```

A run executes six lanes and writes one report per lane under `target/corpus-eval/`:

| Lane | Report | What it is |
|---|---|---|
| `agent-on` | `corpus-eval.md` | The agent attached as `fields=true,collections=true`, with Surefire's own classes excluded from weaving (#561). Every number the write-up quotes comes from here. |
| `agent-off` | `corpus-eval-agent-off.md` | The same subjects with nothing attached. The control: `DetectorFeeds` says the 20 agent-fed detectors have no input without the agent, so this lane must observe nothing from them, and `CorpusGates` asserts it. |
| `recording` | `corpus-eval-recording.md` | The same libraries, with bodies that call the recording API. A different measurement over a different denominator, which is why it writes its own report and is never merged into the other two. |
| `agent-pairs` | `corpus-eval-agent-pairs.md` | The mirror image of the recording lane, for the 20 agent-fed detectors it cannot reach. The agent is attached and the body records nothing: the difference between a firing row and its silent twin is a field declaration, and everything in between comes from the weaver. `AgentRowPremise` fails the lane if a body here touches the recording API. Some pairs call the JDK type from the test file; others call only Guava, Jackson, HikariCP, Spring, commons-lang3 or Groovy, so the woven call sits inside the library, and `LibraryReach` accounts for which agent-fed detectors that reaches. |
| `agent-pairs-library-excluded` | `corpus-eval-agent-pairs-library-excluded.md` | The agent-pair lane's library rows again, with every corpus library on the agent's `excludes=` list. A library pair claims its finding came from a JDK call inside the library, so with the library unwoven each firing row must go silent. `CorpusGates.checkLibraryExclusionLane` fails on a row that still fires, on a library row whose package the excludes list does not cover, and on a row that did not run in full. A row that uses a library type but whose woven call site is the test body (`Corpus.BODY_CALL_SITE_ROWS`, the JCTools hand-off pair) makes no such claim and does not run here. |
| `idioms` | `corpus-eval-idioms.md` | Correct user-code concurrency, the way a user writes it, with every detector on and the agent attached: a mutable object handed through a `BlockingQueue`, plain data published by a volatile flag, a latch, a guarded wait loop, a pool checked out through a queue. Each correct row is followed by its broken twin, the same code with the synchronization removed or misplaced, which must wake its named detector. A correct row fails the run on any finding at `FACT` tier or above from any detector (a graded detector's finding is read at its evidence cap), and on any finding at all from the detector it names. Correct idioms the happens-before model does not see yet are listed in `Corpus.idiomKnownGaps()` with the reason, and must keep reporting until a fix closes them. `IdiomRowPremise` fails the lane if a body records outside the rows `Corpus.idiomManualApiRows()` names. |

Those files are the source of truth for a given run; the document under `docs/` is a copy of one.

The attached lane uses `-javaagent:` at JVM startup rather than the library's self-attach, and
`CorpusGates` fails the run if that changes. This module is where the self-attach defect in
[#321](https://github.com/PIsberg/async-test-lib/issues/321) was measured: the attach's own
retransformation pass loads the classes it describes, those loads were handed to no transformer
and covered by no snapshot, and detection moved from 20 of 20 documented-unsafe subjects to 6 of
20 with nothing else changed. The agent now discovers with `DiscoveryStrategy.Reiterating` and
reports any class it never consulted, so the underlying gap is closed - but a launch flag still
takes the file order out of the measurement entirely, and that is worth keeping for a number
other people are asked to trust.

## Exposure, and why the reports lead with it

A finding count with no denominator cannot be read. The two unmodified lanes record nothing by
hand, so 141 of the 146 detectors are never fed there at all, and their zero means "never ran",
not "looked and found nothing". The recording lane exists to move a named handful out of that
column, and it counts a detector as exposed only if a subject actually records to it - claiming
the whole feed would trade one unreadable denominator for another. Each report therefore prints, before any rate: how many detectors the lane could
feed, split by `DetectorFeed`, and per exposed detector how many documented-safe and
documented-unsafe subjects it was exposed to. `CorpusGates` fails the run if a detector reports
that the feed table says cannot be fed, so those denominators are checked rather than asserted.

## Where the tests live

The subjects live in `com.example.corpus`, not under the library's own package root. That is
deliberate: `CollectionAccessWeaver` excludes `se.deversity.asynctest.` from collection
substitution to stop the recording path from re-entering itself, so a corpus in that namespace
would have measured a narrower path than a user's suite does.
`TestBodyCollectionIsObservedTest` pins the canonical case that depends on it: a `HashMap` shared
by the test body itself is reported. It runs in the attached lane only.

## Adding a subject

1. Find a class whose **own javadoc** states its thread-safety contract. An inferred contract is
   not ground truth and does not belong here. A class that states nothing is not a subject, however
   obvious its behaviour looks: that is why the corpus carries Netty's allocator and none of its
   buffers.
2. Add a `Subject` row to `Corpus.java` quoting that sentence, with the file and line in the
   library's sources jar. Unpack it **outside the repository**:
   `mvn dependency:copy -Dartifact=<ga>:<version>:jar:sources -DoutputDirectory=$TMPDIR/corpus-sources`.
   Third-party sources anywhere under the repo root end up in the architecture diagrams,
   because `tools/generate-architecture-diagrams.sh` scans `.` for the module picture, and
   the Guardrail job then fails on drift that exists only on your machine.
3. Add one `@AsyncTest` method to `CorpusEvalTest` named exactly as the row's `testMethod`. Use
   `unsafeOperation(...)` for a documented-unsafe subject, since corruption there can surface as a
   thrown exception that the eval counts rather than fails on, and `safeOperation(...)` otherwise.
4. Add the library to `docs/DEPENDENCIES.md` (section 6) with the reason it earns a row.
5. Run it. `CorpusGates` fails if a method has no row, if a row has no method, if a
   documented-thread-safe subject draws a VERDICT-tier HIGH or CRITICAL finding, if a detector the
   feed table says cannot be fed reports anyway, or if the control lane hears from the agent-fed
   pair.
6. Keep what it drew. Decide the rows before the first run, and do not drop a documented-safe
   subject because it drew a finding, or keep one because it stayed quiet: either choice shrinks the
   printed bound without making the claim any stronger. A finding on a documented-safe subject is
   the result, and it goes into an issue and the write-up rather than out of the corpus.

## Adding a recording-lane subject

Different rules, because the body cooperates and the class contract is no longer the ground truth.

1. Add subjects **in pairs**. One direction proves nothing: a `MUST_FIRE` row alone is passed by a
   detector that fires on everything, and a `MUST_STAY_SILENT` row alone by one that was never
   wired up. Both halves should hand the detector the same shape of evidence and differ only in
   the thing it is supposed to notice.
2. Add two `RecordingSubject` rows to `Corpus.java` naming the `DetectorType`, the expectation and
   the rationale. The rationale is printed in the report and in the failure message, so write it
   as the argument for why that outcome follows from the recorded calls - not as a restatement of
   the expectation.
3. Add the `@AsyncTest` methods to `CorpusRecordingLaneTest`, named exactly as the rows'
   `testMethod`. Call `CorpusRecorder.countBodyExecution()` first, and register a shared subject
   once for the run rather than per worker: a per-thread `registerX` scatters one subject across
   duplicate entries and the cross-thread contention becomes invisible exactly when it is real.
4. Check the expectation really is structural. If it depends on a particular interleaving being
   observed, it belongs in the unmodified lanes' group-level gate instead - a flaky gate is worse
   than no gate.
5. Run it. `CorpusGates.checkRecordingLane` fails if a method has no row, if a row has no method,
   if a recorded-to detector is not exposed, or if any subject's outcome differs from what its
   row states.

## Adding an idiom-lane row

1. Write the idiom the way a user writes it, in `CorpusIdiomLaneTest`, and record nothing. If no
   woven call site can show a detector the idiom (a thread the runner did not start, a
   `ThreadLocalRandom`, a `java.util.Random`), record what the body did and add the row to
   `Corpus.idiomManualApiRows()` with that reason.
2. Add its broken twin directly after it: the same code with the synchronization removed or put
   in the wrong place. Declare the two `RecordingSubject` rows in that order;
   `CorpusGates.everyCorrectIdiomHasABrokenTwin` reads the order.
3. Name the detector the idiom is about on the correct row, and the one the twin must wake on the
   twin, with the severity the lane report shows it firing at.
4. Run it. If the correct row draws a finding, that is the result: fix the detector with a unit
   test in the library, or, if the fix is large, list the row in `Corpus.idiomKnownGaps()` with
   what the model is missing. Never reshape the idiom to dodge a finding.
5. A shared object that has to be fresh per round, and a writer role, come from `Rounds`, a
   ticket counter over objects built before the run. Build a per-round object rather than one
   shared across the whole run: an object that outlives its round is read as a construction that
   later rounds corroborate, which excuses exactly the accesses the row is about.
