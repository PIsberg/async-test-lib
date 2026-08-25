package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.TaskQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TaskQueue demonstrating the NotifyWithoutMonitorDetector.
 *
 * The concurrent test shows how calling notify() without holding the monitor
 * on the queue object is flagged as an illegal monitor state violation.
 */
class TaskQueueTest {

    private TaskQueue taskQueue;

    @BeforeEach
    void setUp() {
        taskQueue = new TaskQueue();
    }

    @Test
    void test_singleThread_add_doesNotThrow() {
        // add() swallows the IllegalMonitorStateException internally
        assertDoesNotThrow(() -> taskQueue.add("task1"));
    }

    @Test
    void test_singleThread_size_afterAdd() {
        taskQueue.add("task2");
        assertEquals(1, taskQueue.size());
    }

    @Disabled("Remove @Disabled to see bug detected by NotifyWithoutMonitorDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectNotifyWithoutMonitor = true, failOn = FailOn.LOW)
    void test_concurrent_detectsBug() {
        // Record the notify() attempt on the queue object without holding its monitor.
        // Thread.holdsLock(queue) is false here — that is the bug.
        AsyncTestContext.notifyWithoutMonitorDetector()
                .recordNotifyAttempt(taskQueue.queue, "TaskQueue.queue");

        // Drive the actual buggy method
        taskQueue.add("concurrent-task-" + Thread.currentThread().getId());
    }
}
