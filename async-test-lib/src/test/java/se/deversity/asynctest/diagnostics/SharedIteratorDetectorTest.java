package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Spliterator;

import static org.junit.jupiter.api.Assertions.*;

class SharedIteratorDetectorTest {

    @Test
    void cleanWhenNoAccess() {
        var d = new SharedIteratorDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void singleThreadAccessIsNotFlagged() {
        var d = new SharedIteratorDetector();
        List<String> list = new ArrayList<>(List.of("a", "b", "c"));
        Iterator<String> it = list.iterator();
        while (it.hasNext()) {
            d.recordAccess(it, "hasNext");
            it.next();
            d.recordAccess(it, "next");
        }
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void sharedIteratorAcrossThreadsIsFlagged() throws Exception {
        var d = new SharedIteratorDetector();
        List<String> list = new ArrayList<>(List.of("a", "b", "c"));
        Iterator<String> it = list.iterator();
        d.recordAccess(it, "hasNext");
        d.recordAccess(it, "next");
        Thread t = new Thread(() -> {
            d.recordAccess(it, "hasNext");
            d.recordAccess(it, "next");
        });
        t.start();
        t.join();

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("Iterator"));
        assertTrue(msg.contains("2 threads"));
        assertTrue(msg.contains("own monitor count as guarded"));
        assertEquals(1, report.structuredViolations.size());
        assertEquals("SharedIterator", report.structuredViolations.get(0).detector());
        assertEquals("Iterator", report.structuredViolations.get(0).attributes().get("kind"));
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
        assertTrue(report.toString().contains("SHARED ITERATOR"));
    }

    @Test
    void sharedListIteratorAcrossThreadsIsFlagged() throws Exception {
        var d = new SharedIteratorDetector();
        List<String> list = new ArrayList<>(List.of("a", "b", "c"));
        ListIterator<String> it = list.listIterator();
        d.recordAccess(it, "next");
        Thread t = new Thread(() -> d.recordAccess(it, "remove"));
        t.start();
        t.join();

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertEquals("ListIterator", report.structuredViolations.get(0).attributes().get("kind"));
    }

    @Test
    void sharedSpliteratorAcrossThreadsIsFlagged() throws Exception {
        var d = new SharedIteratorDetector();
        List<String> list = new ArrayList<>(List.of("a", "b", "c"));
        Spliterator<String> sp = list.spliterator();
        d.recordAccess(sp, "tryAdvance");
        Thread t = new Thread(() -> d.recordAccess(sp, "forEachRemaining"));
        t.start();
        t.join();

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertEquals("Spliterator", report.structuredViolations.get(0).attributes().get("kind"));
        assertTrue(report.violations.get(0).contains("tryAdvance") || report.violations.get(0).contains("forEachRemaining"));
    }

    @Test
    void distinctInstancesAreTrackedSeparately() throws Exception {
        var d = new SharedIteratorDetector();
        List<String> list = new ArrayList<>(List.of("a", "b", "c"));
        Iterator<String> shared = list.iterator();
        Iterator<String> solo = list.iterator();

        d.recordAccess(shared, "hasNext");
        d.recordAccess(solo, "hasNext");
        Thread t = new Thread(() -> d.recordAccess(shared, "next"));
        t.start();
        t.join();

        var report = d.analyze();
        assertEquals(1, report.violations.size());
    }

    @Test
    void nullsAreIgnored() {
        var d = new SharedIteratorDetector();
        d.recordAccess(null, "next");
        List<String> list = new ArrayList<>(List.of("a"));
        Iterator<String> it = list.iterator();
        d.recordAccess(it, null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void analyzeIsIdempotent() throws Exception {
        var d = new SharedIteratorDetector();
        List<String> list = new ArrayList<>(List.of("a", "b", "c"));
        Iterator<String> it = list.iterator();
        d.recordAccess(it, "hasNext");
        Thread t = new Thread(() -> d.recordAccess(it, "next"));
        t.start();
        t.join();

        var first = d.analyze();
        var second = d.analyze();
        assertEquals(first.violations, second.violations);
        assertEquals(first.structuredViolations.size(), second.structuredViolations.size());
        assertEquals(
                first.structuredViolations.get(0).message(),
                second.structuredViolations.get(0).message());
    }
}
