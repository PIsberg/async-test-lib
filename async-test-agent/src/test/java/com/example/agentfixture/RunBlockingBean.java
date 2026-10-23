package com.example.agentfixture;

import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.scheduling.CoroutineScheduler;

/**
 * Calls Kotlin's {@code runBlocking} the way compiled Kotlin does, against the facade class's real
 * name, recording nothing (#1007). The fixture package is in the agent's {@code includes=}, so the
 * call sites here are woven, and the woven {@code Thread.start} attributes the worker to the run.
 */
public class RunBlockingBean {

    /**
     * Starts a coroutine worker running {@code Dispatchers.IO} work that enters {@code runBlocking},
     * as a coroutine calling a blocking API built on {@code runBlocking} does, and waits for it.
     *
     * @throws InterruptedException if interrupted while joining
     */
    public void runBlockingOnACoroutineWorker() throws InterruptedException {
        Thread worker = new CoroutineScheduler.Worker(RunBlockingBean::bridge,
                CoroutineScheduler.WorkerState.BLOCKING);
        worker.start();
        worker.join();
    }

    /** Enters {@code runBlocking} on the calling thread, which is what it is for. */
    public void runBlockingOnTheCaller() {
        bridge();
    }

    private static void bridge() {
        BuildersKt.runBlocking(null, () -> { });
    }
}
