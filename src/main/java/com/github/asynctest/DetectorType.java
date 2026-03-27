package com.github.asynctest;

/**
 * Enumerates all available detectors for type-safe opt-outs.
 * Used with {@link AsyncTest#excludes()}.
 */
public enum DetectorType {
    // Phase 1
    DEADLOCKS,
    VISIBILITY,
    LIVELOCKS,

    // Phase 2: Core
    FALSE_SHARING,
    WAKEUP_ISSUES,
    CONSTRUCTOR_SAFETY,
    ABA_PROBLEM,
    LOCK_ORDER,
    SYNCHRONIZERS,
    THREAD_POOL,
    MEMORY_ORDERING,
    ASYNC_PIPELINE,
    READ_WRITE_LOCK_FAIRNESS,

    // Phase 2: Monitors
    SEMAPHORE,
    COMPLETABLE_FUTURE_EXCEPTIONS,
    CONCURRENT_MODIFICATIONS,
    LOCK_LEAKS,
    SHARED_RANDOM,
    BLOCKING_QUEUE,
    CONDITION_VARIABLES,
    SIMPLE_DATE_FORMAT,
    PARALLEL_STREAMS,
    RESOURCE_LEAKS,

    // Phase 2: Additional Concurrency
    COUNTDOWN_LATCH,
    CYCLIC_BARRIER,
    REENTRANT_LOCK,
    VOLATILE_ARRAY,
    DOUBLE_CHECKED_LOCKING,
    WAIT_TIMEOUT,

    // Phase 3
    RACE_CONDITIONS,
    THREAD_LOCAL_LEAKS,
    BUSY_WAITING,
    ATOMICITY_VIOLATIONS,
    INTERRUPT_MISHANDLING
}
