# Example 68 — Scheduled Executor Leak

Demonstrates **ScheduledExecutorDetector**: a `HealthCheckService` creates a
`ScheduledExecutorService` and schedules periodic tasks but never calls
`shutdown()`. Each test run leaves a live thread in the background, causing
thread accumulation and potentially interfering with subsequent tests.

## The Problem

`HealthCheckService.startChecks()` creates a single-thread scheduled executor
and schedules a fixed-rate task. There is no `shutdown()` call — not in a
`@AfterEach`, not via `AutoCloseable`, not ever. Every `startChecks()` call
leaks one daemon thread. The detector checks whether all registered executors
were shut down by the end of the test round.

## How to Reproduce

1. Remove `@Disabled` from `testStartChecks_concurrent_detectsExecutorLeak`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **ScheduledExecutorDetector** report listing executors
   that were never shut down.

**Fix**: implement `AutoCloseable` and call `scheduler.shutdown()` in `close()`,
or expose a `stop()` method and call it in `@AfterEach`.
