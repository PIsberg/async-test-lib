# Example 64 — Phaser Misuse

Demonstrates **PhaserDetector**: a `Phaser` registered with too few parties
causes timeout or unexpected termination when more threads try to arrive than
were registered.

## The Problem

`MultiPhaseProcessor` creates a `Phaser(2)` — registering exactly 2 parties —
but 3 threads each call `arriveAndAwaitAdvance()`. The third arrival exceeds
the registered party count. Depending on JVM behaviour the phaser either
throws `IllegalStateException`, terminates prematurely, or leaves threads
blocked indefinitely at the phase boundary.

The detector observes that the number of `arrive()` calls exceeds the
registered party count and reports the mismatch as a phaser issue.

## How to Reproduce

1. Remove `@Disabled` from `testRunPhase_concurrent_detectsPhaserMisuse`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **PhaserDetector** report showing a timed-out or
   terminated phaser.

**Fix**: register the phaser with the correct number of parties — one per
thread that will call `arrive*()` — or use `phaser.register()` dynamically
before each arrival.
