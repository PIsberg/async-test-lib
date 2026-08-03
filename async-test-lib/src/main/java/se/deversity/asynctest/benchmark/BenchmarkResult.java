package se.deversity.asynctest.benchmark;

import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Represents benchmark results for a single test method.
 * Stores execution times and statistics for comparison across runs.
 */
public final class BenchmarkResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private final @Nullable String testClass;
    private final @Nullable String testMethod;
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

    /** {@return the test class} */
    public @Nullable String getTestClass() {
        return testClass;
    }

    /** {@return the test method} */
    public @Nullable String getTestMethod() {
        return testMethod;
    }

    /** {@return the timestamp} */
    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    /** {@return the threads} */
    public int getThreads() {
        return threads;
    }

    /** {@return the invocations} */
    public int getInvocations() {
        return invocations;
    }

    /** {@return the total execution time in nanoseconds} */
    public long getTotalExecutionTimeNanos() {
        return totalExecutionTimeNanos;
    }

    /** {@return the avg time per invocation in nanoseconds} */
    public long getAvgTimePerInvocationNanos() {
        return avgTimePerInvocationNanos;
    }

    /** {@return the min time per invocation in nanoseconds} */
    public long getMinTimePerInvocationNanos() {
        return minTimePerInvocationNanos;
    }

    /** {@return the max time per invocation in nanoseconds} */
    public long getMaxTimePerInvocationNanos() {
        return maxTimePerInvocationNanos;
    }

    /** {@return the invocation times in nanoseconds} */
    public List<Long> getInvocationTimesNanos() {
        return Collections.unmodifiableList(invocationTimesNanos);
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
            return String.format(Locale.ROOT, "%.2f µs", nanos / 1_000.0);
        } else if (nanos < 1_000_000_000) {
            return String.format(Locale.ROOT, "%.2f ms", nanos / 1_000_000.0);
        } else {
            return String.format(Locale.ROOT, "%.2f s", nanos / 1_000_000_000.0);
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
        return Objects.equals(testClass, that.testClass) &&
               Objects.equals(testMethod, that.testMethod) &&
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
        private @Nullable String testClass;
        private @Nullable String testMethod;
        private LocalDateTime timestamp = LocalDateTime.now(ZoneId.systemDefault());
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
