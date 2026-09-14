# Example 64 — Phaser Misuse

Demonstrates **PhaserDetector**: a `Phaser` registered with too few parties
leaves a thread waiting at a phase boundary that never advances.

## The Problem

`MultiPhaseProcessor` creates a `Phaser(2)` — registering exactly 2 parties —
but 3 threads each call `arriveAndAwaitAdvance()`. Two of them pair up and
advance the phase; the third arrives in the next phase and waits for a partner
that never comes.

The test body gives that wait a deadline and records the timeout. The detector
reads the real phaser when the run is analyzed: the phase the wait gave up on
is still the current phase, with a party not arrived, so the phase never
advanced and the timeout is reported. A timeout whose phase advanced later is
not reported, and neither is termination on its own (#587).

## How to Reproduce

1. Remove `@Disabled` from `testRunPhase_concurrent_detectsPhaserMisuse`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **PhaserDetector** report showing a stalled phase.

**Fix**: register the phaser with the correct number of parties — one per
thread that will call `arrive*()` — or use `phaser.register()` dynamically
before each arrival.
