package com.github.asynctest.diagnostics;

/**
 * Auto-fix suggestions for concurrency issues.
 *
 * <p>Provides copy-paste ready code fixes for common concurrency problems.
 *
 * @since 1.3.0
 */
public final class AutoFix {

    private AutoFix() { }

    // ============= Deadlock Fixes =============

    public static String getDeadlockFix() {
        return """
            💡 AUTO-FIX: How to Fix This Deadlock

            Option 1: Ensure consistent lock ordering (RECOMMENDED)
            ─────────────────────────────────────────────────────────
            // Before (causes deadlock):
            synchronized(lock1) { synchronized(lock2) { } }  // Thread A
            synchronized(lock2) { synchronized(lock1) { } }  // Thread B

            // After (fixed - always acquire lock1 first):
            synchronized(lock1) { synchronized(lock2) { } }  // Thread A
            synchronized(lock1) { synchronized(lock2) { } }  // Thread B


            Option 2: Use tryLock with timeout
            ─────────────────────────────────────────────────────────
            ReentrantLock lock1 = new ReentrantLock();
            ReentrantLock lock2 = new ReentrantLock();

            if (lock1.tryLock(1, TimeUnit.SECONDS)) {
                try {
                    if (lock2.tryLock(1, TimeUnit.SECONDS)) {
                        try {
                            // Critical section
                        } finally {
                            lock2.unlock();
                        }
                    }
                } finally {
                    lock1.unlock();
                }
            }


            Option 3: Use a single lock
            ─────────────────────────────────────────────────────────
            private final Object singleLock = new Object();

            synchronized (singleLock) {
                // All critical sections use the same lock
            }
            """;
    }

    // ============= Race Condition Fixes =============

    public static String getRaceConditionFix() {
        return """
            💡 AUTO-FIX: How to Fix This Race Condition

            Option 1: Make field volatile (for simple reads/writes)
            ─────────────────────────────────────────────────────────
            // Before:
            private long balance;

            // After:
            private volatile long balance;


            Option 2: Use AtomicReference (for compound operations)
            ─────────────────────────────────────────────────────────
            // Before:
            private long balance;
            balance += amount;  // NOT thread-safe!

            // After:
            private final AtomicLong balance = new AtomicLong(0);
            balance.addAndGet(amount);  // Thread-safe!


            Option 3: Use synchronized methods
            ─────────────────────────────────────────────────────────
            // Before:
            public void deposit(long amount) {
                balance += amount;
            }

            // After:
            public synchronized void deposit(long amount) {
                balance += amount;
            }


            Option 4: Use explicit locks
            ─────────────────────────────────────────────────────────
            private final ReentrantLock lock = new ReentrantLock();
            private long balance;

            public void deposit(long amount) {
                lock.lock();
                try {
                    balance += amount;
                } finally {
                    lock.unlock();
                }
            }
            """;
    }

    // ============= Visibility Fixes =============

    public static String getVisibilityFix() {
        return """
            💡 AUTO-FIX: How to Fix This Visibility Issue

            Option 1: Add volatile keyword
            ─────────────────────────────────────────────────────────
            // Before:
            private boolean ready = false;

            // After:
            private volatile boolean ready = false;


            Option 2: Use AtomicBoolean
            ─────────────────────────────────────────────────────────
            // Before:
            private boolean ready = false;

            // After:
            private final AtomicBoolean ready = new AtomicBoolean(false);

            // Usage:
            ready.set(true);           // Writer
            if (ready.get()) { }       // Reader


            Option 3: Use synchronized
            ─────────────────────────────────────────────────────────
            private boolean ready = false;

            public synchronized void setReady() {
                ready = true;
            }

            public synchronized boolean isReady() {
                return ready;
            }
            """;
    }

    // ============= False Sharing Fixes =============

    public static String getFalseSharingFix() {
        return """
            💡 AUTO-FIX: How to Fix False Sharing

            Option 1: Use @Contended annotation (Java 8+)
            ─────────────────────────────────────────────────────────
            import sun.misc.Contended;

            @Contended
            private volatile long valueA;

            @Contended
            private volatile long valueB;

            Note: Add -XX:+EnableContended JVM flag for Java 8-10
                  (enabled by default in Java 11+)


            Option 2: Add padding bytes
            ─────────────────────────────────────────────────────────
            private volatile long valueA;
            private long p1, p2, p3, p4, p5, p6, p7; // Padding (56 bytes)
            private volatile long valueB;


            Option 3: Group by access pattern
            ─────────────────────────────────────────────────────────
            // Group frequently-written fields separately:
            private static class HotFields {
                @Contended
                volatile long counterA;

                @Contended
                volatile long counterB;
            }

            // Group read-only fields together:
            private static class ColdFields {
                final String name;
                final int id;
                // These can share cache lines safely
            }
            """;
    }

    // ============= CompletableFuture Leak Fixes =============

    public static String getCompletableFutureLeakFix() {
        return """
            💡 AUTO-FIX: How to Fix CompletableFuture Completion Leak

            Option 1: Use try-finally to guarantee completion
            ─────────────────────────────────────────────────────────
            CompletableFuture<String> future = new CompletableFuture<>();

            try {
                String result = doWork();
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
            // Finally block ensures completion even if complete() throws
            finally {
                if (!future.isDone()) {
                    future.completeExceptionally(new IllegalStateException("Unexpected"));
                }
            }


            Option 2: Use CompletableFuture.supplyAsync (RECOMMENDED)
            ─────────────────────────────────────────────────────────
            // Let CompletableFuture handle completion automatically:
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                return doWork();  // Exceptions automatically handled
            });


            Option 3: Add timeout as safety net
            ─────────────────────────────────────────────────────────
            CompletableFuture<String> future = new CompletableFuture<>();

            // Complete normally in your code
            future.complete(result);

            // Safety net: complete exceptionally if timeout expires
            future.orTimeout(5, TimeUnit.SECONDS);
            """;
    }

    // ============= Virtual Thread Pinning Fixes =============

    public static String getVirtualThreadPinningFix() {
        return """
            💡 AUTO-FIX: How to Fix Virtual Thread Pinning

            Option 1: Replace synchronized with ReentrantLock
            ─────────────────────────────────────────────────────────
            // Before (causes pinning):
            synchronized (lock) {
                doWork();
            }

            // After (no pinning):
            ReentrantLock lock = new ReentrantLock();
            lock.lock();
            try {
                doWork();
            } finally {
                lock.unlock();
            }


            Option 2: Use concurrent collections
            ─────────────────────────────────────────────────────────
            // Before (synchronized HashMap):
            private final Map<K, V> cache = Collections.synchronizedMap(new HashMap<>());

            // After (ConcurrentHashMap - no synchronization needed):
            private final ConcurrentHashMap<K, V> cache = new ConcurrentHashMap<>();


            Option 3: Use platform threads for synchronized code
            ─────────────────────────────────────────────────────────
            ExecutorService pinnedExecutor = Executors.newFixedThreadPool(10);

            CompletableFuture.runAsync(() -> {
                synchronized (lock) {
                    doWork();  // Runs on platform thread, no pinning
                }
            }, pinnedExecutor);
            """;
    }

    // ============= Thread Pool Deadlock Fixes =============

    public static String getThreadPoolDeadlockFix() {
        return """
            💡 AUTO-FIX: How to Fix Thread Pool Deadlock

            Option 1: Use a separate executor for nested tasks (RECOMMENDED)
            ─────────────────────────────────────────────────────────
            ExecutorService mainPool = Executors.newFixedThreadPool(4);
            ExecutorService nestedPool = Executors.newCachedThreadPool();

            mainPool.submit(() -> {
                // Submit nested task to DIFFERENT executor
                CompletableFuture<Void> nested = CompletableFuture.runAsync(
                    () -> doNestedWork(),
                    nestedPool  // ← Different executor!
                );
                nested.join();
            });


            Option 2: Use CompletableFuture.supplyAsync with different executor
            ─────────────────────────────────────────────────────────
            ExecutorService mainPool = Executors.newFixedThreadPool(4);
            ExecutorService nestedPool = Executors.newCachedThreadPool();

            CompletableFuture<String> parent = CompletableFuture.supplyAsync(
                () -> doParentWork(),
                mainPool
            );

            CompletableFuture<String> child = parent.thenApplyAsync(
                result -> doChildWork(result),
                nestedPool  // ← Different executor!
            );


            Option 3: Don't wait for nested tasks
            ─────────────────────────────────────────────────────────
            // Fire-and-forget nested task (don't call .get() or .join())
            mainPool.submit(() -> {
                nestedPool.submit(() -> doNestedWork());
                // Continue without waiting
            });
            """;
    }

    // ============= Busy Waiting Fixes =============

    public static String getBusyWaitingFix() {
        return """
            💡 AUTO-FIX: How to Fix Busy Waiting

            Option 1: Use wait()/notify()
            ─────────────────────────────────────────────────────────
            // Before (busy waiting):
            while (!condition) {
                // Spins continuously, wastes CPU!
            }

            // After (proper waiting):
            synchronized (lock) {
                while (!condition) {
                    lock.wait();  // Releases CPU, wakes when notified
                }
            }

            // In another thread, when condition becomes true:
            synchronized (lock) {
                lock.notifyAll();
            }


            Option 2: Use CountDownLatch
            ─────────────────────────────────────────────────────────
            CountDownLatch latch = new CountDownLatch(1);

            // Waiting thread:
            latch.await();  // Blocks efficiently

            // Signaling thread:
            latch.countDown();


            Option 3: Use LockSupport.park()
            ─────────────────────────────────────────────────────────
            // Low-level waiting (advanced):
            while (!condition) {
                LockSupport.park(this);  // Efficient parking
            }

            // To unpark:
            LockSupport.unpark(thread);
            """;
    }

    // ============= Atomicity Violation Fixes =============

    public static String getAtomicityViolationFix() {
        return """
            💡 AUTO-FIX: How to Fix Atomicity Violation

            Option 1: Use AtomicLong/AtomicReference
            ─────────────────────────────────────────────────────────
            // Before (not atomic):
            private long counter;
            counter++;  // READ, ADD, WRITE - can interleave!

            // After (atomic):
            private final AtomicLong counter = new AtomicLong(0);
            counter.incrementAndGet();  // Atomic!


            Option 2: Use LongAdder (better for high contention)
            ─────────────────────────────────────────────────────────
            private final LongAdder counter = new LongAdder();
            counter.increment();  // Better performance under contention
            long total = counter.sum();


            Option 3: Use synchronized
            ─────────────────────────────────────────────────────────
            private long counter;

            public synchronized void increment() {
                counter++;  // Now atomic
            }
            """;
    }

    // ============= Lock Leak Fixes =============

    public static String getLockLeakFix() {
        return """
            💡 AUTO-FIX: How to Fix Lock Leak

            Option 1: Always use try-finally (RECOMMENDED)
            ─────────────────────────────────────────────────────────
            // Before (lock leak on exception):
            lock.lock();
            doWork();  // If this throws, lock is never released!
            lock.unlock();

            // After (always releases lock):
            lock.lock();
            try {
                doWork();
            } finally {
                lock.unlock();  // Always executes, even on exception
            }


            Option 2: Use try-with-resources (ReentrantLock doesn't support this)
            ─────────────────────────────────────────────────────────
            // For AutoCloseable locks, create a wrapper:
            class LockedResource implements AutoCloseable {
                private final Lock lock;
                LockedResource(Lock lock) { this.lock = lock; lock.lock(); }
                public void close() { lock.unlock(); }
            }

            try (LockedResource r = new LockedResource(lock)) {
                doWork();  // Lock automatically released
            }


            Option 3: Use synchronized instead
            ─────────────────────────────────────────────────────────
            // synchronized automatically releases on exit:
            synchronized (lock) {
                doWork();  // Lock released even on exception
            }
            """;
    }
}
