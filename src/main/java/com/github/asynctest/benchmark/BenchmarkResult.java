package com.github.asynctest.benchmark;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents benchmark results for a single test method.
 * Stores execution times and statistics for comparison across runs.
 */
public class BenchmarkResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String testClass;
    private final String testMethod;
    private final LocalDateTime timestamp;
    private final int threads;
    private final int invocations;
    private final long totalExecutionTimeNanos;
    private final long avgTimePerInvocationNanos;
    private final long minTimePerInvocationNanos;
    private final long maxTimePerInvocationNanos;
    private final List<Long> invocationTimesNanos;

    private BenchmarkResult(Builder builder) {
        this.testClass = builder.testClass;
        this.testMethod = builder.testMethod;
        this.timestamp = builder.timestamp;
        this.threads = builder.threads;
        this.invocations = builder.invocations;
        this.totalExecutionTimeNanos = builder.totalExecutionTimeNanos;
        this.avgTimePerInvocationNanos = builder.avgTimePerInvocationNanos;
        this.minTimePerInvocationNanos = builder.minTimePerInvocationNanos;
        this.maxTimePerInvocationNanos = builder.maxTimePerInvocationNanos;
        this.invocationTimesNanos = new ArrayList<>(builder.invocationTimesNanos);
    }

    public String getTestClass() {
        return testClass;
    }

    public String getTestMethod() {
        return testMethod;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public int getThreads() {
        return threads;
    }

    public int getInvocations() {
        return invocations;
    }

    public long getTotalExecutionTimeNanos() {
        return totalExecutionTimeNanos;
    }

    public long getAvgTimePerInvocationNanos() {
        return avgTimePerInvocationNanos;
    }

    public long getMinTimePerInvocationNanos() {
        return minTimePerInvocationNanos;
    }

    public long getMaxTimePerInvocationNanos() {
        return maxTimePerInvocationNanos;
    }

    public List<Long> getInvocationTimesNanos() {
        return invocationTimesNanos;
    }

    /**
     * Get a unique key for this benchmark (class + method).
     */
    public String getBenchmarkKey() {
        return testClass + "#" + testMethod;
    }

    /**
     * Calculate the standard deviation of invocation times.
     */
    public double getStandardDeviation() {
        if (invocationTimesNanos.size() <= 1) {
            return 0.0;
        }
        double avg = avgTimePerInvocationNanos;
        double sumSquaredDiff = 0.0;
        for (long time : invocationTimesNanos) {
            double diff = time - avg;
            sumSquaredDiff += diff * diff;
        }
        return Math.sqrt(sumSquaredDiff / (invocationTimesNanos.size() - 1));
    }

    /**
     * Format time in nanoseconds to a human-readable string.
     */
    public static String formatTime(long nanos) {
        if (nanos < 1_000) {
            return nanos + " ns";
        } else if (nanos < 1_000_000) {
            return String.format("%.2f µs", nanos / 1_000.0);
        } else if (nanos < 1_000_000_000) {
            return String.format("%.2f ms", nanos / 1_000_000.0);
        } else {
            return String.format("%.2f s", nanos / 1_000_000_000.0);
        }
    }

    @Override
    public String toString() {
        return String.format(
            "BenchmarkResult{%s#%s, threads=%d, invocations=%d, total=%s, avg=%s, min=%s, max=%s, stddev=%s}",
            testClass,
            testMethod,
            threads,
            invocations,
            formatTime(totalExecutionTimeNanos),
            formatTime(avgTimePerInvocationNanos),
            formatTime(minTimePerInvocationNanos),
            formatTime(maxTimePerInvocationNanos),
            formatTime((long) getStandardDeviation())
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BenchmarkResult that = (BenchmarkResult) o;
        return testClass.equals(that.testClass) &&
               testMethod.equals(that.testMethod) &&
               timestamp.equals(that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(testClass, testMethod, timestamp);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String testClass;
        private String testMethod;
        private LocalDateTime timestamp = LocalDateTime.now();
        private int threads;
        private int invocations;
        private long totalExecutionTimeNanos;
        private long avgTimePerInvocationNanos;
        private long minTimePerInvocationNanos;
        private long maxTimePerInvocationNanos;
        private List<Long> invocationTimesNanos = new ArrayList<>();

        public Builder testClass(String testClass) {
            this.testClass = testClass;
            return this;
        }

        public Builder testMethod(String testMethod) {
            this.testMethod = testMethod;
            return this;
        }

        public Builder timestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        public Builder invocations(int invocations) {
            this.invocations = invocations;
            return this;
        }

        public Builder totalExecutionTimeNanos(long totalExecutionTimeNanos) {
            this.totalExecutionTimeNanos = totalExecutionTimeNanos;
            return this;
        }

        public Builder avgTimePerInvocationNanos(long avgTimePerInvocationNanos) {
            this.avgTimePerInvocationNanos = avgTimePerInvocationNanos;
            return this;
        }

        public Builder minTimePerInvocationNanos(long minTimePerInvocationNanos) {
            this.minTimePerInvocationNanos = minTimePerInvocationNanos;
            return this;
        }

        public Builder maxTimePerInvocationNanos(long maxTimePerInvocationNanos) {
            this.maxTimePerInvocationNanos = maxTimePerInvocationNanos;
            return this;
        }

        public Builder invocationTimesNanos(List<Long> invocationTimesNanos) {
            this.invocationTimesNanos = new ArrayList<>(invocationTimesNanos);
            return this;
        }

        public BenchmarkResult build() {
            return new BenchmarkResult(this);
        }
    }
}
