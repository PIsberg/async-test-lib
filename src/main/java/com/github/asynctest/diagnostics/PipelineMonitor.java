package com.github.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors event flow in async pipelines and detects signal loss.
 * 
 * Problems detected:
 * - Events/messages lost in processing pipeline
 * - Subscribers miss published events
 * - Deadletter/unhandled events
 */
public class PipelineMonitor {
    
    private static class PipelineStage {
        final String stageName;
        final AtomicLong published = new AtomicLong(0);
        final AtomicLong processed = new AtomicLong(0);
        final AtomicLong failed = new AtomicLong(0);
        final Set<String> lostEvents = ConcurrentHashMap.newKeySet();
        
        PipelineStage(String name) {
            this.stageName = name;
        }
    }
    
    private final Map<String, PipelineStage> stages = new ConcurrentHashMap<>();
    private final List<String> eventLog = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean enabled = true;
    
    /**
     * Register a pipeline stage.
     */
    public void registerStage(String stageName) {
        stages.putIfAbsent(stageName, new PipelineStage(stageName));
    }
    
    /**
     * Record event published to a stage.
     */
    public void recordEventPublished(String stageName, String eventId) {
        if (!enabled) return;
        
        PipelineStage stage = stages.computeIfAbsent(stageName, PipelineStage::new);
        stage.published.incrementAndGet();
        eventLog.add(String.format("PUBLISH %s -> %s", eventId, stageName));
    }
    
    /**
     * Record event processed by a stage.
     */
    public void recordEventProcessed(String stageName, String eventId) {
        if (!enabled) return;
        
        PipelineStage stage = stages.get(stageName);
        if (stage == null) return;
        
        stage.processed.incrementAndGet();
        eventLog.add(String.format("PROCESS %s in %s", eventId, stageName));
    }
    
    /**
     * Record event failure.
     */
    public void recordEventFailed(String stageName, String eventId, String reason) {
        if (!enabled) return;
        
        PipelineStage stage = stages.get(stageName);
        if (stage == null) return;
        
        stage.failed.incrementAndGet();
        stage.lostEvents.add(eventId + ": " + reason);
        eventLog.add(String.format("FAILED %s in %s: %s", eventId, stageName, reason));
    }
    
    /**
     * Analyze pipeline for signal loss.
     */
    public PipelineReport analyzePipeline() {
        PipelineReport report = new PipelineReport();
        
        for (PipelineStage stage : stages.values()) {
            long published = stage.published.get();
            long processed = stage.processed.get();
            long failed = stage.failed.get();
            long unaccounted = published - processed - failed;
            
            if (unaccounted > 0) {
                report.missingEvents.add(String.format(
                    "%s: %d published, %d processed, %d failed, %d unaccounted",
                    stage.stageName, published, processed, failed, unaccounted
                ));
            }
            
            if (failed > 0) {
                report.failedEvents.put(stage.stageName, new ArrayList<>(stage.lostEvents));
            }
            
            if (processed < published * 0.9) {
                report.lowProcessingRate.add(String.format(
                    "%s: Only %.1f%% of events processed",
                    stage.stageName, (100.0 * processed) / Math.max(1, published)
                ));
            }
        }
        
        return report;
    }
    
    public void reset() {
        stages.clear();
        eventLog.clear();
    }
    
    public void disable() {
        enabled = false;
    }
    
    public void enable() {
        enabled = true;
    }
    
    public static class PipelineReport {
        public final Set<String> missingEvents = new HashSet<>();
        public final Map<String, List<String>> failedEvents = new HashMap<>();
        public final Set<String> lowProcessingRate = new HashSet<>();
        
        public boolean hasIssues() {
            return !missingEvents.isEmpty() || !failedEvents.isEmpty();
        }
        
        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No pipeline signal loss detected.";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("PIPELINE ISSUES DETECTED:\n");
            
            if (!missingEvents.isEmpty()) {
                sb.append("\nMissing events:\n");
                for (String issue : missingEvents) {
                    sb.append("  - ").append(issue).append("\n");
                }
            }
            
            if (!failedEvents.isEmpty()) {
                sb.append("\nFailed events:\n");
                for (Map.Entry<String, List<String>> entry : failedEvents.entrySet()) {
                    sb.append("  ").append(entry.getKey()).append(":\n");
                    for (String event : entry.getValue()) {
                        sb.append("    - ").append(event).append("\n");
                    }
                }
            }
            
            if (!lowProcessingRate.isEmpty()) {
                sb.append("\nLow processing rates:\n");
                for (String issue : lowProcessingRate) {
                    sb.append("  - ").append(issue).append("\n");
                }
            }
            
            return sb.toString();
        }
    }
}
