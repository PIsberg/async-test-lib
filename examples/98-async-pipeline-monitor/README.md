# Example 98 — Async Pipeline Monitor

Demonstrates **PipelineMonitor** detecting stage imbalance and event loss.

## The Problem

`DataPipeline.processMessage()` runs three stages in sequence — parse (fast), enrich
(sometimes slow, up to 10 ms), and persist (always slow, 20 ms). Stages are not
back-pressure aware: a slow persist stage cannot signal the enrich stage to slow down,
so messages pile up and some are dropped or fail when the calling thread is interrupted
or a timeout expires.

## How to Reproduce

1. Remove `@Disabled` from `test_concurrent_detectsPipelineImbalance` in
   `DataPipelineTest`.
2. Run the test:
   ```
   mvn test
   gradle test
   ```
3. **PipelineMonitor** will report stages where published events outnumber processed
   events, and stages with failed events.

## The Fix

Add back-pressure coordination between stages — for example, use a `BlockingQueue`
between each stage with a bounded capacity so fast producers slow down when consumers
fall behind.
