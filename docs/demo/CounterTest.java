import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;

class CounterTest {

    private int counter = 0;        // plain int — NOT thread-safe

    @AsyncTest(
        threads     = 8,            // 8 threads race at the barrier
        invocations = 50,           // 50 rounds of maximum contention
        detectAll   = true          // all 139 detectors enabled
    )
    void counter_mustBeThreadSafe() {
        AsyncTestContext.raceConditionMonitor()
            .recordFieldRead(this, "counter");

        int value = counter;        // read

        AsyncTestContext.raceConditionMonitor()
            .recordFieldWrite(this, "counter");

        counter = value + 1;        // BUG: non-atomic read-modify-write
    }
}
