package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;

class NonAtomicConcurrentMapUpdateDetectorTest {

    @Test
    void cleanWhenNoAccess() {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void singleThreadCheckThenActIsNotFlagged() {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
        for (int i = 0; i < 5; i++) {
            d.recordCheckThenAct(map, "k", "lazy-fill", Thread.currentThread());
        }
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void sameMapAndKeyAcrossThreadsIsFlagged() throws Exception {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
        d.recordCheckThenAct(map, "user-1", "cache-fill", Thread.currentThread());
        Thread t = new Thread(() -> d.recordCheckThenAct(map, "user-1", "cache-fill", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("cache-fill"));
        assertTrue(msg.contains("user-1"));
        assertTrue(msg.contains("2 threads"));
        assertTrue(msg.contains("putIfAbsent"));
        assertEquals(1, report.structuredViolations.size());
        assertEquals("NonAtomicConcurrentMapUpdate", report.structuredViolations.get(0).detector());
        assertEquals(2, report.structuredViolations.get(0).attributes().get("threadCount"));
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
    }

    @Test
    void differentKeysOnSameMapAreTrackedSeparately() throws Exception {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
        d.recordCheckThenAct(map, "a", "op", Thread.currentThread());
        d.recordCheckThenAct(map, "b", "op", Thread.currentThread());
        Thread t = new Thread(() -> d.recordCheckThenAct(map, "a", "op", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        // only key "a" was touched by two threads
        assertEquals(1, report.violations.size());
        assertTrue(report.violations.get(0).contains("'a'"));
    }

    @Test
    void differentMapsAreTrackedSeparately() throws Exception {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, String> m1 = new ConcurrentHashMap<>();
        ConcurrentMap<String, String> m2 = new ConcurrentHashMap<>();
        d.recordCheckThenAct(m1, "k", "op", Thread.currentThread());
        d.recordCheckThenAct(m2, "k", "op", Thread.currentThread());
        Thread t = new Thread(() -> d.recordCheckThenAct(m1, "k", "op", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertEquals(1, report.violations.size());
    }

    @Test
    void nullsAreIgnored() {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        d.recordCheckThenAct(null, "k", "op", Thread.currentThread());
        d.recordCheckThenAct(new ConcurrentHashMap<>(), "k", "op", null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void nullKeyIsHandled() throws Exception {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
        d.recordCheckThenAct(map, null, "op", Thread.currentThread());
        Thread t = new Thread(() -> d.recordCheckThenAct(map, null, "op", Thread.currentThread()));
        t.start();
        t.join();
        assertTrue(d.analyze().hasIssues());
    }
}
