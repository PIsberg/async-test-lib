package com.github.asynctest.benchmark;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkResultTest {

    private static BenchmarkResult sample(long avg, long min, long max, List<Long> times) {
        return BenchmarkResult.builder()
                .testClass("com.example.MyTest")
                .testMethod("myMethod")
                .timestamp(LocalDateTime.of(2026, 1, 1, 0, 0))
                .threads(4)
                .invocations(10)
                .totalExecutionTimeNanos(avg * times.size())
                .avgTimePerInvocationNanos(avg)
                .minTimePerInvocationNanos(min)
                .maxTimePerInvocationNanos(max)
                .invocationTimesNanos(times)
                .build();
    }

    // ---- Builder and getters ----

    @Test
    void builder_populatesAllFields() {
        LocalDateTime ts = LocalDateTime.of(2026, 4, 1, 12, 0);
        List<Long> times = List.of(100L, 200L, 300L);

        BenchmarkResult r = BenchmarkResult.builder()
                .testClass("Foo")
                .testMethod("bar")
                .timestamp(ts)
                .threads(3)
                .invocations(5)
                .totalExecutionTimeNanos(600L)
                .avgTimePerInvocationNanos(200L)
                .minTimePerInvocationNanos(100L)
                .maxTimePerInvocationNanos(300L)
                .invocationTimesNanos(times)
                .build();

        assertEquals("Foo", r.getTestClass());
        assertEquals("bar", r.getTestMethod());
        assertEquals(ts, r.getTimestamp());
        assertEquals(3, r.getThreads());
        assertEquals(5, r.getInvocations());
        assertEquals(600L, r.getTotalExecutionTimeNanos());
        assertEquals(200L, r.getAvgTimePerInvocationNanos());
        assertEquals(100L, r.getMinTimePerInvocationNanos());
        assertEquals(300L, r.getMaxTimePerInvocationNanos());
        assertEquals(times, r.getInvocationTimesNanos());
    }

    // ---- getBenchmarkKey ----

    @Test
    void getBenchmarkKey_combinesClassAndMethod() {
        BenchmarkResult r = sample(100L, 50L, 200L, List.of(100L));
        assertEquals("com.example.MyTest#myMethod", r.getBenchmarkKey());
    }

    // ---- formatTime ----

    @Test
    void formatTime_nanoseconds() {
        assertEquals("0 ns",   BenchmarkResult.formatTime(0));
        assertEquals("1 ns",   BenchmarkResult.formatTime(1));
        assertEquals("999 ns", BenchmarkResult.formatTime(999));
    }

    @Test
    void formatTime_microseconds_boundary() {
        // 1000 ns = 1.00 µs (lower boundary)
        assertEquals("1.00 µs", BenchmarkResult.formatTime(1_000));
        // 999_000 ns = 999.00 µs (within microseconds range, just below 1_000_000)
        assertEquals("999.00 µs", BenchmarkResult.formatTime(999_000));
    }

    @Test
    void formatTime_milliseconds() {
        assertEquals("1.00 ms", BenchmarkResult.formatTime(1_000_000));
        assertEquals("1.50 ms", BenchmarkResult.formatTime(1_500_000));
    }

    @Test
    void formatTime_seconds() {
        assertEquals("1.00 s", BenchmarkResult.formatTime(1_000_000_000));
        assertEquals("2.50 s", BenchmarkResult.formatTime(2_500_000_000L));
    }

    // ---- getStandardDeviation ----

    @Test
    void getStandardDeviation_singleValue_returnsZero() {
        BenchmarkResult r = sample(100L, 100L, 100L, List.of(100L));
        assertEquals(0.0, r.getStandardDeviation(), 0.001);
    }

    @Test
    void getStandardDeviation_emptyList_returnsZero() {
        BenchmarkResult r = sample(0L, 0L, 0L, List.of());
        assertEquals(0.0, r.getStandardDeviation(), 0.001);
    }

    @Test
    void getStandardDeviation_uniformValues_returnsZero() {
        BenchmarkResult r = sample(100L, 100L, 100L, List.of(100L, 100L, 100L));
        assertEquals(0.0, r.getStandardDeviation(), 0.001);
    }

    @Test
    void getStandardDeviation_multipleValues_computesCorrectly() {
        // Values: 100, 200, 300 → avg = 200, squared diffs = 10000+0+10000, variance = 10000, stddev = 100
        BenchmarkResult r = sample(200L, 100L, 300L, List.of(100L, 200L, 300L));
        assertEquals(100.0, r.getStandardDeviation(), 0.001);
    }

    // ---- equals and hashCode ----

    @Test
    void equals_sameClassMethodTimestamp_returnsTrue() {
        LocalDateTime ts = LocalDateTime.of(2026, 1, 1, 0, 0);
        BenchmarkResult a = BenchmarkResult.builder()
                .testClass("A").testMethod("m").timestamp(ts)
                .avgTimePerInvocationNanos(100L).invocationTimesNanos(List.of()).build();
        BenchmarkResult b = BenchmarkResult.builder()
                .testClass("A").testMethod("m").timestamp(ts)
                .avgTimePerInvocationNanos(999L).invocationTimesNanos(List.of()).build();

        assertEquals(a, b, "equals is based on class+method+timestamp only");
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentMethod_returnsFalse() {
        LocalDateTime ts = LocalDateTime.of(2026, 1, 1, 0, 0);
        BenchmarkResult a = BenchmarkResult.builder()
                .testClass("A").testMethod("m1").timestamp(ts)
                .invocationTimesNanos(List.of()).build();
        BenchmarkResult b = BenchmarkResult.builder()
                .testClass("A").testMethod("m2").timestamp(ts)
                .invocationTimesNanos(List.of()).build();

        assertNotEquals(a, b);
    }

    @Test
    void equals_sameInstance_returnsTrue() {
        BenchmarkResult r = sample(100L, 100L, 100L, List.of(100L));
        assertEquals(r, r);
    }

    @Test
    void equals_null_returnsFalse() {
        BenchmarkResult r = sample(100L, 100L, 100L, List.of(100L));
        assertNotEquals(null, r);
    }

    @Test
    void equals_differentType_returnsFalse() {
        BenchmarkResult r = sample(100L, 100L, 100L, List.of(100L));
        assertNotEquals("not a BenchmarkResult", r);
    }

    // ---- toString ----

    @Test
    void toString_containsKeyInformation() {
        BenchmarkResult r = sample(1_500_000L, 1_000_000L, 2_000_000L, List.of(1_500_000L));
        String s = r.toString();
        assertTrue(s.contains("com.example.MyTest"), "should contain class name");
        assertTrue(s.contains("myMethod"), "should contain method name");
        assertTrue(s.contains("threads=4"), "should contain threads");
    }

    // ---- invocationTimesNanos is defensively copied ----

    @Test
    void builder_invocationTimesNanos_isDefensivelyCopied() {
        List<Long> original = new java.util.ArrayList<>(List.of(100L, 200L));
        BenchmarkResult r = BenchmarkResult.builder()
                .testClass("X").testMethod("y")
                .invocationTimesNanos(original)
                .build();

        original.add(999L); // mutate after build
        assertEquals(2, r.getInvocationTimesNanos().size(), "builder should copy the list");
    }
}
