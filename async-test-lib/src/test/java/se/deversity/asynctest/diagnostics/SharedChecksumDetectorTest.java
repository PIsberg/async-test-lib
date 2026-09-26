package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.zip.Adler32;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class SharedChecksumDetectorTest {

    @Test
    void cleanWhenNoAccess() {
        var d = new SharedChecksumDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void singleThreadAccessIsNotFlagged() {
        var d = new SharedChecksumDetector();
        var crc = new CRC32();
        for (int i = 0; i < 5; i++) {
            d.recordAccess(crc, "update", Thread.currentThread());
        }
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void sharedCrc32AcrossThreadsIsFlagged() throws Exception {
        var d = new SharedChecksumDetector();
        var crc = new CRC32();
        d.recordAccess(crc, "update", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(crc, "getValue", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("CRC32"));
        assertTrue(msg.contains("2 threads"));
        assertTrue(msg.contains("update"));
        assertTrue(msg.contains("getValue"));
        assertTrue(msg.contains("own monitor count as guarded"));
        assertEquals(1, report.structuredViolations.size());
        assertEquals("SharedChecksum", report.structuredViolations.get(0).detector());
        assertEquals(2, report.structuredViolations.get(0).attributes().get("threadCount"));
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
    }

    @Test
    void sharedAdler32AcrossThreadsIsFlagged() throws Exception {
        var d = new SharedChecksumDetector();
        var adler = new Adler32();
        d.recordAccess(adler, "update", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(adler, "update", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("Adler32"));
    }

    @Test
    void unnamedThreadsAreListedApartById() {
        // #798: a default virtual thread has no name, so the round's name set held one blank
        // entry for every such thread and the report printed "2 threads ()".
        var d = new SharedChecksumDetector();
        var crc = new CRC32();
        Thread first = Thread.ofVirtual().unstarted(() -> { });
        Thread second = Thread.ofVirtual().unstarted(() -> { });
        d.recordAccess(crc, "update", first);
        d.recordAccess(crc, "update", second);
        d.recordAccess(crc, "getValue", first);
        assertEquals(Set.of("#" + first.threadId(), "#" + second.threadId()),
                reportedThreads(d.analyze().violations.get(0)));
    }

    @Test
    void namedThreadsAreListedByName() {
        var d = new SharedChecksumDetector();
        var crc = new CRC32();
        d.recordAccess(crc, "update", Thread.ofPlatform().name("alpha").unstarted(() -> { }));
        d.recordAccess(crc, "update", Thread.ofPlatform().name("beta").unstarted(() -> { }));
        assertEquals(Set.of("alpha", "beta"), reportedThreads(d.analyze().violations.get(0)));
    }

    /** The thread list a report prints as {@code accessed from N threads (a, b)}. */
    private static Set<String> reportedThreads(String msg) {
        int open = msg.indexOf("threads (") + "threads (".length();
        return Set.copyOf(Arrays.asList(msg.substring(open, msg.indexOf(')', open)).split(", ", -1)));
    }

    @Test
    void distinctInstancesAreTrackedSeparately() throws Exception {
        var d = new SharedChecksumDetector();
        var a = new CRC32();
        var b = new CRC32();
        d.recordAccess(a, "update", Thread.currentThread());
        d.recordAccess(b, "update", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(a, "update", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertEquals(1, report.violations.size());
        assertTrue(report.violations.get(0).contains("CRC32"));
    }

    @Test
    void nullsAreIgnored() {
        var d = new SharedChecksumDetector();
        d.recordAccess(null, "update", Thread.currentThread());
        var crc = new CRC32();
        d.recordAccess(crc, "update", null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void analyzeIsIdempotentAndSideEffectFree() throws Exception {
        var d = new SharedChecksumDetector();
        var crc = new CRC32();
        d.recordAccess(crc, "update", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(crc, "update", Thread.currentThread()));
        t.start();
        t.join();
        var first = d.analyze();
        var second = d.analyze();
        assertEquals(first.violations, second.violations);
        assertEquals(first.structuredViolations.size(), second.structuredViolations.size());
    }
}
