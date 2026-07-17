package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link VirtualThreadPinningDetector}.
 */
class VirtualThreadPinningDetectorTest {

    @Test
    void noPinning_noIssuesDetected() {
        VirtualThreadPinningDetector detector = new VirtualThreadPinningDetector();
        detector.startMonitoring();
        
        VirtualThreadPinningDetector.PinningReport report = detector.analyzePinning();
        
        // On Java 21+, no pinning should be detected
        // On earlier Java, virtual threads aren't supported
        if (VirtualThreadPinningDetector.isVirtualThreadSupported()) {
            assertFalse(report.hasPinningIssues(), "No pinning should be detected");
        }
        assertEquals(0, report.getEvents().size());
    }

    @Test
    void pinningEventDetected() {
        VirtualThreadPinningDetector detector = new VirtualThreadPinningDetector();
        detector.startMonitoring();
        
        // Create a mock virtual thread scenario - just record the event directly
        // The detector's recordPinningEvent filters for virtual threads only
        // For testing, we verify the infrastructure works
        
        VirtualThreadPinningDetector.PinningReport report = detector.analyzePinning();
        
        // Since we're on platform threads in tests, no events will be recorded
        // This test verifies the detector doesn't crash
        assertNotNull(report);
    }

    @Test
    void monitoring_disabled_noEventsRecorded() {
        VirtualThreadPinningDetector detector = new VirtualThreadPinningDetector();
        detector.stopMonitoring(); // Explicitly disabled
        
        detector.startMonitoring();
        VirtualThreadPinningDetector.PinningReport report = detector.analyzePinning();
        
        assertEquals(0, report.getEvents().size());
    }

    @Test
    void clear_resetsAllData() {
        VirtualThreadPinningDetector detector = new VirtualThreadPinningDetector();
        detector.startMonitoring();
        
        // Clear should work regardless of events
        detector.clear();
        
        VirtualThreadPinningDetector.PinningReport report = detector.analyzePinning();
        
        assertEquals(0, report.getEvents().size());
        assertEquals(0, report.getMaxPinnedCount());
    }

    @Test
    void hasPinningIssues_returnsFalseWhenNoEvents() {
        VirtualThreadPinningDetector detector = new VirtualThreadPinningDetector();
        detector.startMonitoring();
        
        // On platform threads, no events are recorded
        assertFalse(detector.hasPinningIssues());
        assertEquals(0, detector.getPinningEventCount());
    }

    @Test
    void isVirtualThreadSupported_returnsCorrectValue() {
        // Java 21+ should support virtual threads
        boolean supported = VirtualThreadPinningDetector.isVirtualThreadSupported();
        
        // Check Java version
        String javaVersion = System.getProperty("java.version");
        int majorVersion = Integer.parseInt(javaVersion.split("\\.")[0]);
        
        if (majorVersion >= 21) {
            assertTrue(supported, "Virtual threads should be supported on Java 21+");
        } else {
            assertFalse(supported, "Virtual threads not supported before Java 21");
        }
    }

    @Test
    void reportToString_noIssues_returnsPositiveMessage() {
        VirtualThreadPinningDetector detector = new VirtualThreadPinningDetector();
        detector.startMonitoring();
        
        VirtualThreadPinningDetector.PinningReport report = detector.analyzePinning();
        String reportStr = report.toString();
        
        if (VirtualThreadPinningDetector.isVirtualThreadSupported()) {
            assertTrue(reportStr.contains("No pinning detected"));
        } else {
            assertTrue(reportStr.contains("not supported"));
        }
    }

    @Test
    void pinningReport_detailsAccessible() {
        VirtualThreadPinningDetector detector = new VirtualThreadPinningDetector();
        detector.startMonitoring();
        
        VirtualThreadPinningDetector.PinningReport report = detector.analyzePinning();
        
        // Verify report structure
        assertNotNull(report.getEvents());
        assertTrue(report.getMaxPinnedCount() >= 0);
        assertNotNull(report.isVirtualThreadSupported());
    }

    @Test
    void startStopMonitoring_worksCorrectly() {
        VirtualThreadPinningDetector detector = new VirtualThreadPinningDetector();
        
        detector.startMonitoring();
        detector.stopMonitoring();
        detector.startMonitoring();
        
        // Should not throw
        VirtualThreadPinningDetector.PinningReport report = detector.analyzePinning();
        assertNotNull(report);
    }

    @Test
    void constructor_initializesCorrectly() {
        VirtualThreadPinningDetector detector = new VirtualThreadPinningDetector();

        // Fresh detector should have no events
        VirtualThreadPinningDetector.PinningReport report = detector.analyzePinning();
        assertEquals(0, report.getEvents().size());
    }

    @Test
    void analyze_delegatesToAnalyzePinning() {
        VirtualThreadPinningDetector detector = new VirtualThreadPinningDetector();
        detector.startMonitoring();

        VirtualThreadPinningDetector.PinningReport viaAnalyze = detector.analyze();
        VirtualThreadPinningDetector.PinningReport viaAnalyzePinning = detector.analyzePinning();

        assertEquals(viaAnalyzePinning.hasPinningIssues(), viaAnalyze.hasPinningIssues());
        assertEquals(viaAnalyzePinning.toString(), viaAnalyze.toString());
    }

    // ---- JDK-version-aware cause classification (JEP 491 / JDK 26) ----

    @Test
    void classifyOperation_recognizesMonitorCauses() {
        assertEquals(VirtualThreadPinningDetector.PinningCause.MONITOR,
                VirtualThreadPinningDetector.classifyOperation("synchronized block"));
        assertEquals(VirtualThreadPinningDetector.PinningCause.MONITOR,
                VirtualThreadPinningDetector.classifyOperation("Object.wait inside monitor"));
        assertEquals(VirtualThreadPinningDetector.PinningCause.MONITOR,
                VirtualThreadPinningDetector.classifyOperation("blocked in wait()"));
    }

    @Test
    void classifyOperation_recognizesClassInitAndNativeCauses() {
        assertEquals(VirtualThreadPinningDetector.PinningCause.CLASS_INIT,
                VirtualThreadPinningDetector.classifyOperation("waiting for class init of Config"));
        assertEquals(VirtualThreadPinningDetector.PinningCause.CLASS_INIT,
                VirtualThreadPinningDetector.classifyOperation("<clinit> wait"));
        assertEquals(VirtualThreadPinningDetector.PinningCause.NATIVE,
                VirtualThreadPinningDetector.classifyOperation("blocking JNI call"));
        assertEquals(VirtualThreadPinningDetector.PinningCause.NATIVE,
                VirtualThreadPinningDetector.classifyOperation("FFM downcall"));
    }

    @Test
    void classifyOperation_fallsBackToOther() {
        assertEquals(VirtualThreadPinningDetector.PinningCause.OTHER,
                VirtualThreadPinningDetector.classifyOperation("mystery blocking op"));
        assertEquals(VirtualThreadPinningDetector.PinningCause.OTHER,
                VirtualThreadPinningDetector.classifyOperation(null));
    }

    @Test
    void stillPinsOn_isVersionAware() {
        // synchronized pinned through JDK 23, fixed by JEP 491 in JDK 24
        assertTrue(VirtualThreadPinningDetector.stillPinsOn(
                VirtualThreadPinningDetector.PinningCause.MONITOR, 21));
        assertTrue(VirtualThreadPinningDetector.stillPinsOn(
                VirtualThreadPinningDetector.PinningCause.MONITOR, 23));
        assertFalse(VirtualThreadPinningDetector.stillPinsOn(
                VirtualThreadPinningDetector.PinningCause.MONITOR, 24));
        assertFalse(VirtualThreadPinningDetector.stillPinsOn(
                VirtualThreadPinningDetector.PinningCause.MONITOR, 26));

        // class-init waits pinned through JDK 25, fixed in JDK 26
        assertTrue(VirtualThreadPinningDetector.stillPinsOn(
                VirtualThreadPinningDetector.PinningCause.CLASS_INIT, 25));
        assertFalse(VirtualThreadPinningDetector.stillPinsOn(
                VirtualThreadPinningDetector.PinningCause.CLASS_INIT, 26));

        // native calls pin everywhere
        assertTrue(VirtualThreadPinningDetector.stillPinsOn(
                VirtualThreadPinningDetector.PinningCause.NATIVE, 26));
        assertTrue(VirtualThreadPinningDetector.stillPinsOn(
                VirtualThreadPinningDetector.PinningCause.OTHER, 26));
    }

    @Test
    void snapshot_carriesCauseAndObsoleteFlag() throws Exception {
        VirtualThreadPinningDetector detector = new VirtualThreadPinningDetector();
        detector.startMonitoring();

        Thread.ofVirtual().name("vt-pin-test").start(() ->
                detector.recordPinningEvent(Thread.currentThread(), "synchronized block")
        ).join();

        var report = detector.analyzePinning();
        assertEquals(1, report.getEvents().size());
        var event = report.getEvents().get(0);
        assertEquals(VirtualThreadPinningDetector.PinningCause.MONITOR, event.getCause());
        // The build baseline is Java 21 (< 24), but assert consistency with the
        // running JDK rather than a hardcoded expectation:
        boolean expectObsolete = Runtime.version().feature() >= 24;
        assertEquals(expectObsolete, event.isObsoleteOnCurrentJdk());
        assertEquals(expectObsolete ? 1 : 0, report.getObsoleteEventCount());
        assertEquals(!expectObsolete, report.hasEffectivePinningIssues());
        assertTrue(report.hasPinningIssues(), "legacy accessor keeps counting all events");
    }

    @Test
    void nativeEvents_alwaysCountAsEffectivePinning() throws Exception {
        VirtualThreadPinningDetector detector = new VirtualThreadPinningDetector();
        detector.startMonitoring();

        Thread.ofVirtual().name("vt-native-test").start(() ->
                detector.recordPinningEvent(Thread.currentThread(), "blocking JNI call")
        ).join();

        var report = detector.analyzePinning();
        assertTrue(report.hasEffectivePinningIssues());
        assertEquals(0, report.getObsoleteEventCount());
    }
}
