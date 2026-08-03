package se.deversity.asynctest.diagnostics;

/**
 * Configuration for Virtual Thread stress testing.
 * 
 * With Java 21+ Virtual Threads (Project Loom), we can spawn millions of lightweight threads.
 * However, virtual threads can be pinned to their underlying OS carrier thread if they
 * encounter certain blocking operations (like synchronized blocks).
 * 
 * This config allows testing for thread-pinning issues by scaling up thread counts
 * that would be impossible with platform threads.
 */
public class VirtualThreadStressConfig {
    
    /**
     * Stress level: how aggressively to stress test with virtual threads.
     */
    public enum StressLevel {
        /** 100 virtual threads - basic compatibility */
        LOW(100),
        /** 1,000 virtual threads - moderate stress */
        MEDIUM(1000),
        /** 10,000 virtual threads - serious stress */
        HIGH(10000),
        /** 100,000+ virtual threads - extreme stress (may require -Xmx settings) */
        EXTREME(100000);
        
        /** The thread count. */
        public final int threadCount;
        
        StressLevel(int threadCount) {
            this.threadCount = threadCount;
        }
    }
    
    private final StressLevel stressLevel;
    private final boolean detectThreadPinning;
    private final boolean enableVirtualThreadEvents;
    private final long timeoutMs;
    
    public VirtualThreadStressConfig(StressLevel stressLevel, 
                                     boolean detectThreadPinning,
                                     boolean enableVirtualThreadEvents,
                                     long timeoutMs) {
        this.stressLevel = stressLevel;
        this.detectThreadPinning = detectThreadPinning;
        this.enableVirtualThreadEvents = enableVirtualThreadEvents;
        this.timeoutMs = timeoutMs;
    }
    /**
     * {@return the builder}
     */
    
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * {@return the stress level}
     */
    public StressLevel getStressLevel() {
        return stressLevel;
    }
    
    /**
     * {@return the thread count}
     */
    public int getThreadCount() {
        return stressLevel.threadCount;
    }
    
    /**
     * {@return whether detect thread pinning}
     */
    public boolean isDetectThreadPinning() {
        return detectThreadPinning;
    }
    
    /**
     * {@return whether enable virtual thread events}
     */
    public boolean isEnableVirtualThreadEvents() {
        return enableVirtualThreadEvents;
    }
    
    /**
     * {@return the timeout in milliseconds}
     */
    public long getTimeoutMs() {
        return timeoutMs;
    }
    
    public static class Builder {
        private StressLevel stressLevel = StressLevel.MEDIUM;
        private boolean detectThreadPinning = true;
        private boolean enableVirtualThreadEvents = false;
        private long timeoutMs = 30000; // 30 seconds for extreme stress tests
        /**
         * Stress level.
         *
         * @param level the level
         * @return the stress level
         */
        
        public Builder stressLevel(StressLevel level) {
            this.stressLevel = level;
            return this;
        }
        /**
         * Detect thread pinning.
         *
         * @param detect the detect
         * @return the detect thread pinning
         */
        
        public Builder detectThreadPinning(boolean detect) {
            this.detectThreadPinning = detect;
            return this;
        }
        /**
         * Enable virtual thread events.
         *
         * @param enable the enable
         * @return the enable virtual thread events
         */
        
        public Builder enableVirtualThreadEvents(boolean enable) {
            this.enableVirtualThreadEvents = enable;
            return this;
        }
        /**
         * Timeout in milliseconds.
         *
         * @param timeout the timeout
         * @return the timeout in milliseconds
         */
        
        public Builder timeoutMs(long timeout) {
            this.timeoutMs = timeout;
            return this;
        }
        /**
         * {@return the build}
         */
        
        public VirtualThreadStressConfig build() {
            return new VirtualThreadStressConfig(stressLevel, detectThreadPinning, 
                                                 enableVirtualThreadEvents, timeoutMs);
        }
    }
    
    /**
     * Helper to check if this JVM supports virtual threads (Java 21+).
     *
     * @return the is virtual thread supported
     */
    public static boolean isVirtualThreadSupported() {
        try {
            Thread.class.getMethod("ofVirtual");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
    
    /**
     * Utility to create a virtual thread executor with potential pinning detection.
     * Returns executor class name if virtual threads are available.
     *
     * @return the get virtual thread executor class
     */
    public static String getVirtualThreadExecutorClass() {
        if (isVirtualThreadSupported()) {
            return "java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor";
        }
        return "java.util.concurrent.Executors.newFixedThreadPool";
    }
    
    @Override
    public String toString() {
        return String.format(
            "VirtualThreadStressConfig{stressLevel=%s, threadCount=%d, detectPinning=%b, timeout=%dms}",
            stressLevel, getThreadCount(), detectThreadPinning, timeoutMs
        );
    }
}
