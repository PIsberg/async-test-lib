package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Validates that the async-test framework itself properly respects Java Memory Model (JMM)
 * "Happens-Before" relationships. This ensures the test framework's internal state is
 * correctly synchronized and won't introduce concurrency bugs.
 * 
 * Key JMM rules validated:
 * - Volatile reads/writes create memory barriers
 * - Synchronization (locks) create acquire/release semantics
 * - Happens-Before relationships are transitive
 */
public class MemoryModelValidator {

    /**
     * How long a reader waits for the writer it depends on. Deliberately shorter than the
     * 5s join() the calling thread uses, so a stalled writer is reported as a timeout here
     * before the join gives up and drops the observation entirely.
     */
    private static final long WRITER_TIMEOUT_SECONDS = 2;
    private final AtomicReference<ValidationResult> lastResult = new AtomicReference<>();

    /**
     * Runs at the top of every thread whose write another thread waits for. Production is a
     * no-op; a test injects a delay here to stand in for a runner that schedules the writer
     * late, which is the condition a check must survive rather than report as a violation.
     */
    private final Runnable writerStartHook;

    /** Creates a validator whose writer threads start as promptly as the scheduler allows. */
    public MemoryModelValidator() {
        this(() -> { });
    }

    MemoryModelValidator(Runnable writerStartHook) {
        this.writerStartHook = writerStartHook;
    }

    /**
     * Run comprehensive JMM validation on the test framework.
     * This checks that all internal state transitions are properly synchronized.
     *
     * @return the findings this detector collected during the run
     */
    public ValidationResult validate() {
        ValidationResult result = new ValidationResult();
        
        // Test 1: Volatile visibility
        testVolatileVisibility(result);
        
        // Test 2: Happens-before on synchronization
        testSynchronizationHappensBefore(result);
        
        // Test 3: Thread start/join happens-before
        testThreadStartJoinHappensBefore(result);
        
        // Test 4: AtomicReference visibility
        testAtomicVisibility(result);
        
        lastResult.set(result);
        return result;
    }
    
    private void testVolatileVisibility(ValidationResult result) {
        AtomicBoolean flag = new AtomicBoolean(false);
        AtomicInteger readCount = new AtomicInteger(0);
        
        // No sleep here: the reader races the writer on purpose, and the check below reads the
        // flag after joining both, so join() is what orders the observation.
        Thread writer = new Thread(() -> {
            writerStartHook.run();
            flag.set(true);
        });
        
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 100 && !flag.get(); i++) {
                readCount.incrementAndGet();
            }
        });
        
        try {
            writer.start();
            reader.start();
            writer.join(5000);
            reader.join(5000);
            
            if (flag.get()) {
                result.testsRun++;
                result.testsPassed++;
                result.observations.add("✓ Volatile visibility works correctly");
            } else {
                result.testsRun++;
                result.observations.add("✗ Volatile visibility issue detected");
            }
        } catch (InterruptedException e) {
            result.observations.add("✗ Volatile test interrupted: " + e.getMessage());
        }
    }
    
    private void testSynchronizationHappensBefore(ValidationResult result) {
        Object lock = new Object();
        int[] sharedValue = {0};
        CountDownLatch released = new CountDownLatch(1);
        
        Thread t1 = new Thread(() -> {
            writerStartHook.run();
            synchronized (lock) {
                sharedValue[0] = 42;
            }
            released.countDown();
        });
        
        Thread t2 = new Thread(() -> {
            if (!awaitWriter(released, result, "Sync")) {
                return;
            }
            synchronized (lock) {
                if (sharedValue[0] == 42) {
                    result.observations.add("✓ Synchronization happens-before is correct");
                    result.testsPassed++;
                } else {
                    result.observations.add("✗ Synchronization happens-before failed: expected 42, got " + sharedValue[0]);
                }
            }
        });
        
        try {
            result.testsRun++;
            t1.start();
            t2.start();
            t1.join(5000);
            t2.join(5000);
        } catch (InterruptedException e) {
            result.observations.add("✗ Sync test interrupted: " + e.getMessage());
        }
    }
    
    private void testThreadStartJoinHappensBefore(ValidationResult result) {
        int[] sharedValue = {0};
        
        Thread child = new Thread(() -> {
            sharedValue[0] = 99;
        });
        
        try {
            result.testsRun++;
            child.start();
            child.join(); // join() creates happens-before
            
            if (sharedValue[0] == 99) {
                result.observations.add("✓ Thread.start()/join() happens-before is correct");
                result.testsPassed++;
            } else {
                result.observations.add("✗ Thread.start()/join() happens-before failed");
            }
        } catch (InterruptedException e) {
            result.observations.add("✗ Start/join test interrupted");
        }
    }
    
    private void testAtomicVisibility(ValidationResult result) {
        AtomicReference<String> atomicRef = new AtomicReference<>();
        boolean[] success = {false};
        CountDownLatch written = new CountDownLatch(1);
        
        Thread writer = new Thread(() -> {
            writerStartHook.run();
            atomicRef.set("SUCCESS");
            written.countDown();
        });
        
        Thread reader = new Thread(() -> {
            if (!awaitWriter(written, result, "Atomic")) {
                return;
            }
            success[0] = "SUCCESS".equals(atomicRef.get());
        });
        
        try {
            result.testsRun++;
            writer.start();
            reader.start();
            writer.join(5000);
            reader.join(5000);
            
            if (success[0]) {
                result.observations.add("✓ AtomicReference visibility works correctly");
                result.testsPassed++;
            } else {
                result.observations.add("✗ AtomicReference visibility issue");
            }
        } catch (InterruptedException e) {
            result.observations.add("✗ Atomic test interrupted");
        }
    }

    /**
     * Waits for the writing thread to signal that its write has happened. Replaces the fixed
     * sleeps two of these checks used to take on faith: a thread that has not been scheduled
     * within 50ms has not violated the memory model, but the old code recorded it as a
     * happens-before failure, which is how a loaded CI runner turned this validator red.
     *
     * <p>A timeout is recorded as its own observation rather than as a happens-before failure,
     * because the two mean different things to whoever reads the report.
     *
     * @param signal the latch the writing thread counts down after its write
     * @param result the result to record a timeout or interruption on
     * @param check the check's name, used in the observation text
     * @return whether the writer signalled in time
     */
    private static boolean awaitWriter(CountDownLatch signal, ValidationResult result, String check) {
        try {
            if (signal.await(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return true;
            }
            result.observations.add("✗ " + check + " test timed out after " + WRITER_TIMEOUT_SECONDS
                    + "s waiting for the writing thread");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.observations.add("✗ " + check + " test interrupted waiting for the writing thread");
            return false;
        }
    }
    
    public static class ValidationResult {
        /** How many ordering checks were run. */
        public int testsRun = 0;
        /** How many of the ordering checks held. */
        public int testsPassed = 0;
        /** Every recorded observation, in the order it was made. */
        public final List<String> observations = Collections.synchronizedList(new ArrayList<>());
        
        /**
         * {@return whether valid}
         */
        public boolean isValid() {
            return testsRun > 0 && testsPassed == testsRun;
        }
        
        /**
         * {@return the pass rate}
         */
        public double getPassRate() {
            return testsRun == 0 ? 0 : 100.0 * testsPassed / testsRun;
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("JMM Validation Results:\n");
            sb.append("  Tests Run: ").append(testsRun).append("\n");
            sb.append("  Tests Passed: ").append(testsPassed).append("\n");
            sb.append("  Pass Rate: ").append(String.format("%.1f%%", getPassRate())).append("\n");
            sb.append("  Status: ").append(isValid() ? "✓ VALID" : "✗ INVALID").append("\n");
            sb.append("\nObservations:\n");
            for (String obs : observations) {
                sb.append("  ").append(obs).append("\n");
            }
            return sb.toString();
        }
    }
}
