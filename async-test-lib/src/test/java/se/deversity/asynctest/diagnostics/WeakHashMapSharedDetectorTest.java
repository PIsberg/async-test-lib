package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;

import static org.junit.jupiter.api.Assertions.*;

class WeakHashMapSharedDetectorTest {

    @Test
    void cleanWhenNoAccess() {
        var d = new WeakHashMapSharedDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void sharedWeakHashMapIsFlagged() throws Exception {
        var d = new WeakHashMapSharedDetector();
        var m = new WeakHashMap<String, String>();
        d.recordAccess(m, "weak-cache", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(m, "weak-cache", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("weak-cache"));
        assertTrue(msg.contains("WeakHashMap"));
        assertTrue(msg.contains("infinite loops"),
                "WeakHashMap-specific risk text must appear: " + msg);
    }

    @Test
    void sharedIdentityHashMapIsFlagged() throws Exception {
        var d = new WeakHashMapSharedDetector();
        var m = new IdentityHashMap<Object, Object>();
        d.recordAccess(m, "id-map", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(m, "id-map", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("id-map"));
        assertTrue(msg.contains("IdentityHashMap"));
        assertTrue(msg.contains("linear probing"),
                "IdentityHashMap-specific risk text must appear: " + msg);
    }

    @Test
    void otherMapTypesAreIgnored() throws Exception {
        var d = new WeakHashMapSharedDetector();
        var m = new HashMap<String, String>();
        d.recordAccess(m, "regular-hashmap", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(m, "regular-hashmap", Thread.currentThread()));
        t.start();
        t.join();
        assertFalse(d.analyze().hasIssues(),
                "Regular HashMap is not WeakHashMap or IdentityHashMap — not in scope of THIS detector");
    }

    @Test
    void singleThreadAccessIsNotFlagged() {
        var d = new WeakHashMapSharedDetector();
        var m = new WeakHashMap<String, String>();
        for (int i = 0; i < 5; i++) {
            d.recordAccess(m, "solo", Thread.currentThread());
        }
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void nullsAreIgnored() {
        var d = new WeakHashMapSharedDetector();
        d.recordAccess(null, "x", Thread.currentThread());
        d.recordAccess(new WeakHashMap<>(), "x", null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void structuredViolationCarriesTypeAttribute() throws Exception {
        var d = new WeakHashMapSharedDetector();
        var w = new WeakHashMap<String, String>();
        var i = new IdentityHashMap<Object, Object>();
        d.recordAccess(w, "w-1", Thread.currentThread());
        d.recordAccess(i, "i-1", Thread.currentThread());
        Thread t = new Thread(() -> {
            d.recordAccess(w, "w-1", Thread.currentThread());
            d.recordAccess(i, "i-1", Thread.currentThread());
        });
        t.start();
        t.join();
        var sv = d.analyze().structuredViolations;
        assertEquals(2, sv.size());
        var types = sv.stream().map(v -> v.attributes().get("type")).toList();
        assertTrue(types.contains("WeakHashMap"));
        assertTrue(types.contains("IdentityHashMap"));
    }

    @Test
    void guardedByItsOwnMonitorStaysSilent() throws Exception {
        var d = new WeakHashMapSharedDetector();
        var m = new WeakHashMap<String, String>();
        Runnable guarded = () -> {
            synchronized (m) {
                d.recordAccess(m, "guarded", Thread.currentThread());
            }
        };
        guarded.run();
        Thread t = new Thread(guarded);
        t.start();
        t.join();
        assertFalse(d.analyze().hasIssues(),
                "synchronized (map) is the external synchronization WeakHashMap's javadoc asks "
                        + "for; flagging it reports the fix as loudly as the bug");
    }

    @Test
    void aGuardOnOnlySomeAccessesStillFires() throws Exception {
        var d = new WeakHashMapSharedDetector();
        var m = new WeakHashMap<String, String>();
        synchronized (m) {
            d.recordAccess(m, "half-guarded", Thread.currentThread());
        }
        Thread t = new Thread(() -> d.recordAccess(m, "half-guarded", Thread.currentThread()));
        t.start();
        t.join();
        assertTrue(d.analyze().hasIssues(),
                "one unguarded access empties the candidate lock set; a guard that does not "
                        + "cover every access is no guard");
    }

    // #820: recordAccess counts every access as a write. A WeakHashMap read expunges cleared
    // entries, but the JDK does that inside synchronized (queue), so readers take turns and a read
    // is a read (#807); an IdentityHashMap read writes nothing. recordRead says so.

    @Test
    void readsUnderOneReadLockBesideWritesUnderTheWriteLockStaySilent() throws Exception {
        var d = new WeakHashMapSharedDetector();
        var m = new WeakHashMap<String, String>();
        var lock = new java.util.concurrent.locks.ReentrantReadWriteLock();
        SelfGuard.Scope scope = new SelfGuard.Scope();
        Runnable read = () -> underLock(lock, true,
                () -> d.recordRead(m, "rw-cache", Thread.currentThread()));
        Runnable write = () -> underLock(lock, false,
                () -> d.recordAccess(m, "rw-cache", Thread.currentThread()));

        round(scope, write);
        round(scope, read, read);
        round(scope, write, read, read);

        assertFalse(d.analyze().hasIssues(),
                "puts under the write lock and gets under the read lock is the read-write idiom: "
                        + d.analyze());
    }

    @Test
    void readsAloneInOneRoundBesideAWriteInAnotherStaySilent() throws Exception {
        var d = new WeakHashMapSharedDetector();
        var m = new IdentityHashMap<Object, Object>();
        SelfGuard.Scope scope = new SelfGuard.Scope();
        Runnable read = () -> d.recordRead(m, "warm-map", Thread.currentThread());

        round(scope, () -> d.recordAccess(m, "warm-map", Thread.currentThread()));
        round(scope, read, read);

        assertFalse(d.analyze().hasIssues(),
                "round two only read, and the write ran alone in round one: " + d.analyze());
    }

    @Test
    void anUnguardedReadBesideAWriteInOneRoundIsFlagged() throws Exception {
        var d = new WeakHashMapSharedDetector();
        var m = new WeakHashMap<String, String>();

        round(new SelfGuard.Scope(), () -> d.recordAccess(m, "racy-cache", Thread.currentThread()),
                () -> d.recordRead(m, "racy-cache", Thread.currentThread()));

        assertTrue(d.analyze().hasIssues(), "a write races a read in the same round with no lock held");
    }

    @Test
    void readsUnderOneReadLockBesideAnUnguardedWriteAreFlagged() throws Exception {
        var d = new WeakHashMapSharedDetector();
        var m = new WeakHashMap<String, String>();
        var lock = new java.util.concurrent.locks.ReentrantReadWriteLock();
        Runnable read = () -> underLock(lock, true,
                () -> d.recordRead(m, "half-locked", Thread.currentThread()));

        round(new SelfGuard.Scope(), () -> d.recordAccess(m, "half-locked", Thread.currentThread()),
                read, read);

        assertTrue(d.analyze().hasIssues(), "the write held no lock, so the read lock guards nothing");
    }

    /** Runs {@code body} holding {@code lock}'s read view if {@code shared}, else its write view. */
    private static void underLock(java.util.concurrent.locks.ReentrantReadWriteLock lock,
                                  boolean shared, Runnable body) {
        java.util.concurrent.locks.Lock view = shared ? lock.readLock() : lock.writeLock();
        view.lock();
        HeldLocks.acquired(lock, shared);
        try {
            body.run();
        } finally {
            HeldLocks.released(lock, shared);
            view.unlock();
        }
    }

    /**
     * Starts the next round of {@code scope} and runs each body on a fresh thread with the scope
     * bound, released together so their accesses overlap, as a run's workers are.
     */
    private static void round(SelfGuard.Scope scope, Runnable... bodies) throws InterruptedException {
        scope.markInvocationStart();
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(bodies.length);
        Thread[] workers = new Thread[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            Runnable body = bodies[i];
            workers[i] = new Thread(() -> {
                SelfGuard.Scope.bind(scope);
                try {
                    start.await();
                    body.run();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    SelfGuard.Scope.unbind();
                }
            }, "round-worker-" + i);
            workers[i].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
    }
}
