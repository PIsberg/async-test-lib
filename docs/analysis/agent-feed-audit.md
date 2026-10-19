# Agent-feed overlap audit

Run 2026-10-19 by `AgentFeedOverlapAuditTest` in the agent module, which pins every result below,
so a change to any row fails the build until this page and the test change together.

## The question

Most detectors fire only on what the test body records through their `record*` API. A developer
who does not already know where a bug is will not record it, so on unmodified code those detectors
see nothing, and the ones that matter are the agent-fed ones, which read the woven field,
collection, lock and thread streams. The plan was to move the most common recording-only bug
classes onto the agent feed. Before building any of that, this audit asks the prior question for
five candidates: does an agent-fed detector already report the bug on unmodified code? If it does,
a port only reports one bug twice.

The first answer came from reading javadoc and was wrong. `SharedCollectionDetector` says it
reports "structural modifications during concurrent reads", which reads like the concurrent
modification bug, and on unmodified code it does not report it. Hence an empirical audit.

## Method

Each row is a pair of bodies in `com.example.agentfixture.FeedAuditSubjects`: the bug as a user
writes it, and the fixed twin. Each body runs through `AsyncTestRunner` on two workers lined up on
a `CyclicBarrier`, 20 rounds, with `detectAll = true` and the agent attached with
`fields=true,collections=true`, and the row keeps the detectors that reported. A control row runs
the README's bare `counter++`, which `AtomicityValidator` reports through the agent; without it a
row of silence could mean the harness fed the agent path nothing.

Checked on Windows 11 and JDK 26, three runs, every row identical in all three.

## Results

| Row | Bug, as written | Detectors reporting the buggy body | Detectors reporting the fixed twin |
|---|---|---|---|
| 0, control | `count++` from two threads | `AtomicityValidator` | not applicable |
| 1, `CONCURRENT_MODIFICATIONS` | one thread iterates an `ArrayList` while another adds to it | none | none |
| 2, `VISIBILITY` | data published behind a plain `boolean` flag | none | none |
| 3, `COMPLETABLE_FUTURE_COMPLETION_LEAKS` | a `CompletableFuture` left pending on the exception path | none | none |
| 4, `THREAD_POOL` | a saturated pool's `RejectedExecutionException` caught and dropped | none | none |
| 5, `THREAD_LEAKS` | a thread the body starts and never joins | `DaemonThreadHygieneDetector` | none |

What the rows mean:

- **Concurrent modification is a gap.** In two of the three runs the race also happened, and the
  JDK's fail-fast iterator threw `ConcurrentModificationException` and failed the run: the user
  sees a failing test but no detector naming the cause. In the third run nothing was reported at
  all. The woven iteration records no read, so `SharedCollectionDetector` sees one writer and has
  nothing to report.
- **Visibility is a gap.** One thread writes the data and the flag, the other reads them. No
  thread does a compound read-modify-write, so `AtomicityValidator` stays silent, and no agent-fed
  detector reports a conflicting write and read that the happens-before model leaves unordered.
- **The completion leak and the dropped rejection are gaps.** Nothing on the agent path sees a
  future's lifetime or a rejection.
- **The thread leak is half covered.** `DaemonThreadHygieneDetector` reports the leaked thread for
  its daemon status, which is a different finding from "this thread outlives the test".
  `ThreadLeakDetector` stays silent.
- **No fixed twin drew a finding**, so none of the current agent-fed detectors misfires on these
  correct shapes.

## What follows

Every candidate is a real gap, so each is worth a port, as its own change with a both-directions
test, and each flips its row here and in the test. The likely seams, not yet built:

- **Concurrent modification ([#1052](https://github.com/PIsberg/async-test-lib/issues/1052)):** record the woven `Iterable.iterator()` and the iteration as reads,
  so `SharedCollectionDetector` sees the reader beside the writer, rather than feeding
  `ConcurrentModificationDetector`'s iteration start and end model, which needs an end event the
  weaver does not have.
- **Visibility ([#1053](https://github.com/PIsberg/async-test-lib/issues/1053)):** report a plain field written by one thread and read by another with no
  happens-before edge between them; the field stream already carries both accesses and the model.
- **Completion leak ([#1054](https://github.com/PIsberg/async-test-lib/issues/1054)):** weave `new CompletableFuture<>()` and the completing calls, and report a
  future still pending when the round ends.
- **Dropped rejection ([#1055](https://github.com/PIsberg/async-test-lib/issues/1055)):** weave `Executor.execute`/`submit` and report a
  `RejectedExecutionException` the calling method catches without rethrowing.
- **Thread leak ([#1056](https://github.com/PIsberg/async-test-lib/issues/1056)):** feed `ThreadLeakDetector` from the woven start the runner already attributes
  (`AsyncTestContext.threadSpawned`). It sits in `Preset.ESSENTIALS`, so this adds a PROMPT-tier
  finding to every consumer whose test body starts a thread and does not join it.
