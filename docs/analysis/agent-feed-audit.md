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
| 1, `CONCURRENT_MODIFICATIONS` | one thread iterates an `ArrayList` while another adds to it | none; since #1052 `SharedCollectionDetector` | none |
| 2, `VISIBILITY` | a constant (`data = 42`) published behind a plain `boolean` flag | none; since #1053 `AtomicityValidator` | none |
| 2b, `VISIBILITY` | a computed value published behind the same plain flag | `AtomicityValidator` | not run |
| 3, `COMPLETABLE_FUTURE_COMPLETION_LEAKS` | a `CompletableFuture` left pending on the exception path | none | none |
| 4, `THREAD_POOL` | a saturated pool's `RejectedExecutionException` caught and dropped | none; since #1055 `ThreadPoolMonitor` | none |
| 5, `THREAD_LEAKS` | a thread the body starts and never joins | `DaemonThreadHygieneDetector`; since #1056 also `ThreadLeakDetector` | none |

What the rows mean:

- **Concurrent modification was a gap.** In two of the three runs the race also happened, and the
  JDK's fail-fast iterator threw `ConcurrentModificationException` and failed the run: the user
  saw a failing test but no detector naming the cause. In the third run nothing was reported at
  all. Two things stood in the way: the woven iteration recorded no read, and
  `SharedCollectionDetector` reported one writer only beside two or more readers. #1052 fixed both;
  the row now pins `SharedCollectionDetector`, and on the corpus the change added no finding on any
  of its 100 documented-thread-safe subjects.
- **Visibility is a gap only for constant writes.** `AtomicityValidator` does report a field one
  thread writes and another reads with nothing ordering them (row 2b, added after a first draft of
  this page called all publication a gap). It excuses a field whose every write stores the same
  constant, because such writes cannot lose an update, and in row 2 both `data = 42` and
  `ready = true` are constants. That excuse is right for atomicity and wrong for visibility: the
  classic missing-`volatile` stop flag, `running = false` read in another thread's loop, is a
  constant write, and nothing reported it. Since #1053 `AtomicityValidator` reports it as an
  unordered flag publication when a thread that never writes the field reads it with no lock, no
  happens-before edge and no volatile bit; on the corpus that added no finding on any of its 100
  documented-thread-safe subjects or correct idioms. A `static` flag is not judged yet.
- **The completion leak is a gap; the dropped rejection was one.** Nothing on the agent path sees a
  future's lifetime. Since #1055 the woven `submit` and `execute` record a rejection as it passes
  through, so `ThreadPoolMonitor` reports one the caller dropped.
- **The thread leak was half covered.** `DaemonThreadHygieneDetector` reports the leaked thread for
  its daemon status, which is a different finding from "this thread outlives the test", and
  `ThreadLeakDetector` stayed silent. It is agent-fed since #1056, the first port, and the row now
  pins both.
- **No fixed twin drew a finding**, so none of the current agent-fed detectors misfires on these
  correct shapes.

## What follows

Every candidate is a real gap, so each is worth a port, as its own change with a both-directions
test, and each flips its row here and in the test. The likely seams, not yet built:

- **Concurrent modification ([#1052](https://github.com/PIsberg/async-test-lib/issues/1052), done):** record the woven `Iterable.iterator()` and the iteration as reads,
  so `SharedCollectionDetector` sees the reader beside the writer, rather than feeding
  `ConcurrentModificationDetector`'s iteration start and end model, which needs an end event the
  weaver does not have.
- **Visibility ([#1053](https://github.com/PIsberg/async-test-lib/issues/1053), done for instance fields):** report a plain field whose writes are a constant, written by one thread and
  read by another with no lock and no happens-before edge between them, as a visibility finding
  rather than an atomicity one; the field stream already carries both accesses and the model, and
  the corpus idioms lane decides whether benign racy flags make it too noisy.
- **Completion leak ([#1054](https://github.com/PIsberg/async-test-lib/issues/1054)):** weave `new CompletableFuture<>()` and the completing calls, and report a
  future still pending when the round ends.
- **Dropped rejection ([#1055](https://github.com/PIsberg/async-test-lib/issues/1055), done):** weave `Executor.execute`/`submit` and report a
  `RejectedExecutionException` the calling method catches without rethrowing.
- **Thread leak ([#1056](https://github.com/PIsberg/async-test-lib/issues/1056), done):** feed `ThreadLeakDetector` from the woven start the runner already attributes
  (`AsyncTestContext.threadSpawned`). It sits in `Preset.ESSENTIALS`, so this adds a PROMPT-tier
  finding to every consumer whose test body starts a thread and does not join it.
