package com.github.asynctest.diagnostics;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects CountDownLatch-style misuse such as missing or extra countdowns.
 */
public class LatchMisuseDetector {

    private static class LatchState {
        final String name;
        final int initialCount;
        final AtomicInteger countDownCalls = new AtomicInteger();
        final AtomicInteger awaitCalls = new AtomicInteger();

        LatchState(String name, int initialCount) {
            this.name = name;
            this.initialCount = initialCount;
        }
    }

    private final Map<Integer, LatchState> latches = new ConcurrentHashMap<>();

    public void registerLatch(Object latch, String name, int initialCount) {
        if (latch == null) {
            return;
        }
        latches.putIfAbsent(System.identityHashCode(latch),
            new LatchState(name == null || name.isBlank() ? "CountDownLatch" : name, initialCount));
    }

    public void recordAwait(Object latch) {
        LatchState state = stateFor(latch);
        if (state != null) {
            state.awaitCalls.incrementAndGet();
        }
    }

    public void recordCountDown(Object latch) {
        LatchState state = stateFor(latch);
        if (state != null) {
            state.countDownCalls.incrementAndGet();
        }
    }

    private LatchState stateFor(Object latch) {
        return latch == null ? null : latches.get(System.identityHashCode(latch));
    }

    public LatchMisuseReport analyze() {
        LatchMisuseReport report = new LatchMisuseReport();

        for (LatchState state : latches.values()) {
            if (state.awaitCalls.get() > 0 && state.countDownCalls.get() < state.initialCount) {
                report.missingCountDowns.add(String.format(
                    "%s: awaited %d time(s) but only %d/%d countDown() calls were recorded",
                    state.name,
                    state.awaitCalls.get(),
                    state.countDownCalls.get(),
                    state.initialCount
                ));
            }
            if (state.countDownCalls.get() > state.initialCount) {
                report.extraCountDowns.add(String.format(
                    "%s: countDown() called %d times for initial count %d",
                    state.name,
                    state.countDownCalls.get(),
                    state.initialCount
                ));
            }
        }

        return report;
    }

    public static class LatchMisuseReport {
        public final Set<String> missingCountDowns = new HashSet<>();
        public final Set<String> extraCountDowns = new HashSet<>();

        public boolean hasIssues() {
            return !missingCountDowns.isEmpty() || !extraCountDowns.isEmpty();
        }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No latch misuse detected.";
            }

            StringBuilder sb = new StringBuilder("LATCH MISUSE DETECTED:\n");
            for (String issue : missingCountDowns) {
                sb.append("  - ").append(issue).append('\n');
            }
            for (String issue : extraCountDowns) {
                sb.append("  - ").append(issue).append('\n');
            }
            sb.append("  Fix: ensure every await path has matching countDown() completion paths");
            return sb.toString();
        }
    }
}
