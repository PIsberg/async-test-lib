# Example demonstrations

Part of the [Quality Gates guide](../QUALITY_GATES.md).

## Enabling the disabled example demonstrations

Most examples disable their `@AsyncTest` demonstration on purpose: it demonstrates code that
fails, so leaving it on would make the examples pipeline permanently red. The `@Disabled` reason
carries the claim - "Remove @Disabled to see X detected by YDetector" - and until 2026-08-26
nothing checked it. An audit on 2026-08-25 enabled all 97 by hand and **72 of them passed**. 41 of
those were one missing default (`failOn` is `NONE`, which reports a finding without failing the
run); the remaining 27 were 27 separate faults, tracked in #346.

`ExampleDisabledDemoTest` closed the static half: every disabled `@AsyncTest` must set `failOn`,
so it *can* fail. It cannot tell whether the detector the demonstration names would say anything,
and all 27 passed that check while reporting nothing.

`example-demos.yml` is the dynamic half. It enables every demonstration without editing a file,
using JUnit's own switch, and runs the reactor three times:

```bash
mvn install -DskipTests -Djacoco.skip=true
mvn -f examples/pom.xml test -fae -T 1C -Dlicense.mock.mode=true \
    -Djunit.jupiter.conditions.deactivate=org.junit.jupiter.engine.extension.DisabledCondition
```

`.github/scripts/disabled_demo_report.py` then reads the surefire XML and sorts every
demonstration into: fired every run, **passed every run**, passed every run and known to,
passed in some runs, failed for a reason other than its detector's finding, still skipped, never
ran. Only "passed every run, and not on the known list" fails the job.

`.github/known-silent-demos.txt` is that known list, and it is a record of accepted debt rather
than an exemption. A line needs a reason and an issue; the report prints the entries and their
count on every run, in the log of a *green* job, so the debt cannot fade into the background. The
list is empty as of the fix for #362, and the file keeps the record of what the five entries
turned out to be, because not one of them was what it had been filed as.

Measured over three full runs on 2026-08-26, after #362 and #363:

| | count |
|---|---:|
| fired every run, on the failOn gate | 86 |
| hung, with the timeout naming the detector's finding | 8 |
| passed every run | 1, JDK-bounded (see below) |
| passed in some runs | 0 |
| failed for a reason other than the detector's finding | 0 |
| never ran, or still skipped | 0 |

95 disabled demonstrations in all: three examples lost theirs because no instrumentation could
have made it fire (`07-livelock`, `28-lazy-init`, `30-false-sharing`), which is the second
acceptance path #346 established for `56-lock-downgrade`.

The second row is a distinct bucket rather than a failure. Those eight demonstrate a hang, so the
subject really does stop and the `failOn` gate, which runs on the success path only, is never
reached. Since #363 the timeout carries the names of the detectors that had a finding, which is
what turns "the round timed out" and "here is what was detected" into one sentence. A timeout with
*no* finding named still counts as failing for the wrong reason, and there are none.

The one demonstration that passes is `92-virtual-thread-pinning`, and the reason is the JDK rather
than the code: `synchronized` stopped pinning virtual threads in JDK 24 (JEP 491), so
`VirtualThreadPinningDetector` correctly reports its recorded events as obsolete and has nothing
to gate on. It fires on JDK 21, which is what this job pins, and the numbers above were measured
on JDK 26. Its `@Disabled` reason says so. If the job's JDK is ever raised past 23 it will go red,
with a message naming JEP 491, which is the honest outcome rather than a false alarm.

Three details are deliberate, and each cost something to learn:

- **A passing demonstration is the finding.** That is the opposite of what the examples pipeline
  asserts, which is why this is a separate workflow rather than a flag on that one.
- **Three runs, and only an all-runs pass fails.** Some demonstrations are inherently
  probabilistic; the 2026-08-25 audit found five that fired in one or two runs of three. A gate
  that goes red on one of those gets switched off within a week.
- **Failing for the wrong reason is its own bucket.** A demonstration whose body assertion trips
  before the detector is consulted is not silent, but it is not demonstrating its detector
  either. That is a weaker defect and is reported without failing. Since #363 a hang whose
  timeout names the detector's finding is separated from that bucket again, because the two are
  different defects and only the first is one.

Weekly rather than per-PR because it is slow: 148 modules, three times, several waiting on real
timeouts. The script carries a `--self-test` that the workflow runs first, against known surefire
input, because a job whose entire output is "nothing passed" is worthless if its parser has
quietly stopped matching.

## The demo recording

`docs/diagrams/demo.gif`, the recording in the README, is re-recorded by `demo.yml` **weekly and
on demand**, not on every merge. It used to run on every qualifying push to `main` and propose a
pull request every single time, because a cast is a live capture of a running JVM and can never
come out byte-identical: the header carries the recording's wall clock, every event is prefixed
with elapsed seconds, the split into events follows PTY read timing, and the demo records an
actual race that lands differently each run. Five such pull requests landed between 2026-08-28
and 2026-09-03, each a diff of one binary and fourteen timing lines (#486).

The workflow now compares a **normalised** cast: `tools/demo/normalise-cast.py` keeps only the
output events, joins them so the event split cannot matter, strips ANSI, and masks identity
hashes, thread ids and names, durations, and the verbs in the detector's access-sequence lines.
Measured against the two recordings in #483, 35 differing raw lines come out identical. A pull
request now means the demo's output changed.

Dispatch it by hand before a release, when what the demo prints is the actual question.
