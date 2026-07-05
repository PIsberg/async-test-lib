package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PipelineMonitorTest {

    @Test
    void noStagesReturnNoIssues() {
        PipelineMonitor monitor = new PipelineMonitor();
        PipelineMonitor.PipelineReport report = monitor.analyzePipeline();
        assertFalse(report.hasIssues());
    }

    @Test
    void publishedAndProcessedNoIssues() {
        PipelineMonitor monitor = new PipelineMonitor();
        monitor.registerStage("stage1");
        monitor.recordEventPublished("stage1", "evt-001");
        monitor.recordEventProcessed("stage1", "evt-001");
        PipelineMonitor.PipelineReport report = monitor.analyzePipeline();
        assertFalse(report.hasIssues());
    }

    @Test
    void publishedNotProcessedIsLost() {
        PipelineMonitor monitor = new PipelineMonitor();
        monitor.registerStage("stage1");
        monitor.recordEventPublished("stage1", "evt-002");
        // no recordEventProcessed
        PipelineMonitor.PipelineReport report = monitor.analyzePipeline();
        assertFalse(report.missingEvents.isEmpty());
    }

    @Test
    void failedEventRecorded() {
        PipelineMonitor monitor = new PipelineMonitor();
        monitor.registerStage("ingestion");
        monitor.recordEventPublished("ingestion", "evt-003");
        monitor.recordEventFailed("ingestion", "evt-003", "parse error");
        PipelineMonitor.PipelineReport report = monitor.analyzePipeline();
        assertFalse(report.failedEvents.isEmpty());
        assertTrue(report.failedEvents.containsKey("ingestion"));
    }

    @Test
    void reportToStringWithIssues() {
        PipelineMonitor monitor = new PipelineMonitor();
        monitor.registerStage("transform");
        monitor.recordEventPublished("transform", "evt-004");
        monitor.recordEventFailed("transform", "evt-004", "timeout");
        PipelineMonitor.PipelineReport report = monitor.analyzePipeline();
        String text = report.toString();
        assertNotNull(text);
        assertFalse(text.isBlank());
    }

    @Test
    void reportHasIssuesFalseWhenAllProcessed() {
        PipelineMonitor monitor = new PipelineMonitor();
        monitor.registerStage("output");
        monitor.recordEventPublished("output", "evt-005");
        monitor.recordEventProcessed("output", "evt-005");
        PipelineMonitor.PipelineReport report = monitor.analyzePipeline();
        assertFalse(report.hasIssues());
    }

    @Test
    void resetClearsState() {
        PipelineMonitor monitor = new PipelineMonitor();
        monitor.registerStage("s1");
        monitor.recordEventPublished("s1", "evt-006");
        monitor.reset();
        PipelineMonitor.PipelineReport report = monitor.analyzePipeline();
        assertFalse(report.hasIssues());
        assertTrue(report.missingEvents.isEmpty());
        assertTrue(report.failedEvents.isEmpty());
    }

    @Test
    void disabledSkipsRecording() {
        PipelineMonitor monitor = new PipelineMonitor();
        monitor.disable();
        monitor.registerStage("s2");
        monitor.recordEventPublished("s2", "evt-007");
        PipelineMonitor.PipelineReport report = monitor.analyzePipeline();
        assertFalse(report.hasIssues());
        monitor.enable();
    }

    @Test
    void analyze_delegatesToAnalyzePipeline() {
        PipelineMonitor monitor = new PipelineMonitor();
        monitor.registerStage("stage1");
        monitor.recordEventPublished("stage1", "evt-001");
        monitor.recordEventFailed("stage1", "evt-001", "boom");

        PipelineMonitor.PipelineReport viaAnalyze = monitor.analyze();
        PipelineMonitor.PipelineReport viaAnalyzePipeline = monitor.analyzePipeline();

        assertEquals(viaAnalyzePipeline.hasIssues(), viaAnalyze.hasIssues());
        assertEquals(viaAnalyzePipeline.toString(), viaAnalyze.toString());
    }
}
