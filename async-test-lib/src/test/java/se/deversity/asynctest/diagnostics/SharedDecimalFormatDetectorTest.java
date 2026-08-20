package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import static org.junit.jupiter.api.Assertions.*;

public class SharedDecimalFormatDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new SharedDecimalFormatDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenSingleThread() {
        var d = new SharedDecimalFormatDetector();
        DecimalFormat df = new DecimalFormat("#.##");
        d.recordAccess(df, "df", Thread.currentThread());
        d.recordAccess(df, "df", Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsDecimalFormatShared() throws Exception {
        var d = new SharedDecimalFormatDetector();
        DecimalFormat df = new DecimalFormat("0.00");
        d.recordAccess(df, "df", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(df, "df", Thread.currentThread()));
        t2.start();
        t2.join();
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("df"));
        assertTrue(d.analyze().violations.get(0).contains("2"));
        assertTrue(d.analyze().violations.get(0).contains("own monitor count as guarded"));
    }

    @Test
    void testDetectsNumberFormatShared() throws Exception {
        var d = new SharedDecimalFormatDetector();
        NumberFormat nf = NumberFormat.getCurrencyInstance();
        d.recordAccess(nf, "nf", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(nf, "nf", Thread.currentThread()));
        t2.start();
        t2.join();
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void testSeparateFormatsPerThreadNoIssue() throws Exception {
        var d = new SharedDecimalFormatDetector();
        DecimalFormat df1 = new DecimalFormat("#");
        DecimalFormat df2 = new DecimalFormat("#");
        d.recordAccess(df1, "df1", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(df2, "df2", Thread.currentThread()));
        t2.start();
        t2.join();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testAutoLabelFromClassName() throws Exception {
        var d = new SharedDecimalFormatDetector();
        DecimalFormat df = new DecimalFormat("0");
        d.recordAccess(df, null, Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(df, null, Thread.currentThread()));
        t2.start();
        t2.join();
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("DecimalFormat"));
    }

    @Test
    void testNullSafety() {
        var d = new SharedDecimalFormatDetector();
        DecimalFormat df = new DecimalFormat("#");
        assertDoesNotThrow(() -> {
            d.recordAccess(null, "x", Thread.currentThread());
            d.recordAccess(df, "x", null);
        });
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() throws Exception {
        var d = new SharedDecimalFormatDetector();
        DecimalFormat df = new DecimalFormat("0.0");
        d.recordAccess(df, "df", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(df, "df", Thread.currentThread()));
        t2.start();
        t2.join();
        String s = d.analyze().toString();
        assertTrue(s.contains("SHARED DECIMAL FORMAT"));
        assertTrue(s.contains("Fix"));
    }
}
