# Example 98 — Async Pipeline Monitor

Demonstrates **PipelineMonitor** detecting stage imbalance and event loss.

## The Problem

`DataPipeline.processMessage()` parses, enriches, and then hands the message to the persist
stage through a bounded queue:

```java
boolean accepted = persistQueue.offer(enriched);   // false when full, and nobody looks
```

Parse and enrich are fast. Persist is slow. Once the queue between them fills, every further
message is **dropped** - not failed, dropped. Nothing throws, nothing is logged, and the parse
and enrich counters upstream still say the message was handled.

That is what "no back-pressure" costs. With back-pressure the fast stage waits; without it, the
fast stage is fast and the messages are gone.

## What the monitor reports

`PipelineMonitor` compares published against processed and failed, per stage, and reports what
is left over. A dropped message is published and then neither processed nor failed, so it lands
in exactly that gap:

```
Missing events:
  - persist: 40 published, 4 processed, 0 failed, 36 unaccounted
```

A **failed** message would not produce this finding, and that is the point worth taking away: a
failure is recorded somewhere, and a silent drop is not. The old version of this example recorded
a failure on `InterruptedException` and a success otherwise, which meant published always equalled
processed plus failed, nothing was ever unaccounted for, and the report was empty three runs out
of three (issue #346).

## How to Reproduce

1. Remove `@Disabled` from `test_concurrent_detectsPipelineImbalance` in `DataPipelineTest`.
2. Run the test:
   ```
   mvn test
   gradle test
   ```
3. It fails with the report above. `failOn = FailOn.LOW` is what turns it into a failed run.

You do not need the detector to see the loss:
`testProcessMessage_whenTheHandoffIsFull_dropsSilently` runs on every build and shows three
messages parsed, enriched, and gone.

## The Fix

Add back-pressure coordination between stages — for example, use a `BlockingQueue`
between each stage with a bounded capacity so fast producers slow down when consumers
fall behind.
