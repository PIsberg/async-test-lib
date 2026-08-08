package se.deversity.asynctest.demo;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

/**
 * The lost update: read a counter, add one, write it back, with nothing holding
 * the three steps together. Used by the demo GIF workflow (tools/demo-commands.sh).
 */
class CounterTest {

    // shared by every thread in the run — a plain int, no synchronization
    private int hitCount;

    // includes = exactly this detector and nothing else, so the recording stays
    // about the one finding it is meant to show.
    @AsyncTest(threads = 6, invocations = 3, includes = DetectorType.RACE_CONDITIONS)
    void hitCount_mustNotLoseUpdates() {
        AsyncTestContext.raceConditionDetector().recordFieldRead(this, "hitCount");
        int seen = hitCount;          // 1. read the current value

        Thread.onSpinWait();          // 2. the window another thread slips through

        AsyncTestContext.raceConditionDetector().recordFieldWrite(this, "hitCount");
        hitCount = seen + 1;          // 3. write back a value that may be stale
    }
}
