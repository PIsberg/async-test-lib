package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.ExpensiveResourceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ExpensiveResourceFactory demonstrating the LazyInitRaceDetector.
 *
 * The concurrent test shows how unsynchronized lazy initialization is flagged
 * when multiple threads race on the null-check / field write.
 */
class ExpensiveResourceFactoryTest {

    private ExpensiveResourceFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ExpensiveResourceFactory();
    }

    @Test
    void test_singleThread_returnsResource() {
        ExpensiveResourceFactory.ExpensiveResource r = factory.getResource();
        assertNotNull(r);
    }

    @Test
    void test_singleThread_returnsSameInstance() {
        ExpensiveResourceFactory.ExpensiveResource r1 = factory.getResource();
        ExpensiveResourceFactory.ExpensiveResource r2 = factory.getResource();
        // In single-threaded use the same instance is returned
        assertSame(r1, r2);
    }

    @Disabled("Remove @Disabled to see bug detected by LazyInitRaceDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectLazyInitRace = true, failOn = FailOn.LOW)
    void test_concurrent_detectsBug() {
        // Tell the detector about the null-check and initialization pattern.
        // wasNull=true simulates threads observing null before writing.
        // isVolatile=false because the field has no volatile keyword.
        AsyncTestContext.lazyInitRaceDetector()
                .recordNullCheck("ExpensiveResourceFactory.resource",
                        factory.getResource() == null, false);

        AsyncTestContext.lazyInitRaceDetector()
                .recordInitialization("ExpensiveResourceFactory.resource");

        // Concurrent calls — multiple threads race through the null check
        factory.getResource();
    }
}
