package com.github.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects {@link StringBuilder} instances shared across multiple threads without
 * synchronization.
 *
 * <p>{@code StringBuilder} is explicitly documented as <strong>not thread-safe</strong>.
 * When multiple threads call {@code append()}, {@code insert()}, {@code delete()}, or
 * {@code replace()} on the same instance concurrently, the internal character array can
 * be left in an inconsistent state, producing:
 * <ul>
 *   <li>Garbled / interleaved output strings</li>
 *   <li>{@code StringIndexOutOfBoundsException} from concurrent capacity changes</li>
 *   <li>Data loss (characters silently dropped)</li>
 * </ul>
 *
 * <p>Thread-safe alternatives:
 * <ul>
 *   <li>{@code StringBuffer} — synchronized on every operation (legacy, slower)</li>
 *   <li>{@code ThreadLocal<StringBuilder>} — one builder per thread, combine at the end</li>
 *   <li>Build strings locally per-thread and join with
 *       {@code String.join()} / {@code Collectors.joining()}</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectStringBuilderIssues = true)
 * void testStringBuilderSharing() {
 *     StringBuilder sb = new StringBuilder();
 *     AsyncTestContext.stringBuilderMonitor()
 *         .registerBuilder(sb, "shared-log-builder");
 *
 *     sb.append("entry");
 *     AsyncTestContext.stringBuilderMonitor()
 *         .recordAppend(sb, "shared-log-builder");
 * }
 * }</pre>
 */
public class StringBuilderDetector {

    private static class BuilderState {
        final String name;
        final AtomicInteger appendCount  = new AtomicInteger(0);
        final AtomicInteger insertCount  = new AtomicInteger(0);
        final AtomicInteger deleteCount  = new AtomicInteger(0);
        final AtomicInteger replaceCount = new AtomicInteger(0);
        final AtomicInteger readCount    = new AtomicInteger(0);
        final AtomicInteger errorCount   = new AtomicInteger(0);
        final Set<Long> mutatingThreads  = ConcurrentHashMap.newKeySet();
        final Set<Long> readingThreads   = ConcurrentHashMap.newKeySet();

        BuilderState(String name) {
            this.name = name;
        }
    }

    private final Map<Integer, BuilderState> builders = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Register a {@code StringBuilder} for monitoring.
     *
     * @param builder the StringBuilder to monitor
     * @param name    a descriptive label for reports
     */
    public void registerBuilder(StringBuilder builder, String name) {
        if (!enabled || builder == null) return;
        builders.putIfAbsent(System.identityHashCode(builder),
                new BuilderState(name != null ? name : "StringBuilder@" + System.identityHashCode(builder)));
    }

    /**
     * Record an {@code append()} call.
     *
     * @param builder the StringBuilder instance
     * @param name    the label (should match registration)
     */
    public void recordAppend(StringBuilder builder, String name) {
        recordMutation(builder, name, "append");
    }

    /**
     * Record an {@code insert()} call.
     *
     * @param builder the StringBuilder instance
     * @param name    the label (should match registration)
     */
    public void recordInsert(StringBuilder builder, String name) {
        recordMutation(builder, name, "insert");
    }

    /**
     * Record a {@code delete()} or {@code deleteCharAt()} call.
     *
     * @param builder the StringBuilder instance
     * @param name    the label (should match registration)
     */
    public void recordDelete(StringBuilder builder, String name) {
        recordMutation(builder, name, "delete");
    }

    /**
     * Record a {@code replace()} call.
     *
     * @param builder the StringBuilder instance
     * @param name    the label (should match registration)
     */
    public void recordReplace(StringBuilder builder, String name) {
        recordMutation(builder, name, "replace");
    }

    /**
     * Record a read operation ({@code toString()}, {@code charAt()}, {@code length()}).
     *
     * @param builder the StringBuilder instance
     * @param name    the label (should match registration)
     */
    public void recordRead(StringBuilder builder, String name) {
        if (!enabled || builder == null) return;
        BuilderState state = resolve(builder, name);
        state.readingThreads.add(Thread.currentThread().threadId());
        state.readCount.incrementAndGet();
    }

    /**
     * Record an exception caused by concurrent builder access.
     *
     * @param builder   the StringBuilder instance
     * @param name      the label
     * @param errorType a short description, e.g. "StringIndexOutOfBoundsException"
     */
    public void recordError(StringBuilder builder, String name, String errorType) {
        if (!enabled || builder == null) return;
        BuilderState state = resolve(builder, name);
        state.errorCount.incrementAndGet();
    }

    private void recordMutation(StringBuilder builder, String name, String type) {
        if (!enabled || builder == null) return;
        BuilderState state = resolve(builder, name);
        state.mutatingThreads.add(Thread.currentThread().threadId());
        switch (type) {
            case "append"  -> state.appendCount.incrementAndGet();
            case "insert"  -> state.insertCount.incrementAndGet();
            case "delete"  -> state.deleteCount.incrementAndGet();
            case "replace" -> state.replaceCount.incrementAndGet();
        }
    }

    private BuilderState resolve(StringBuilder builder, String name) {
        int key = System.identityHashCode(builder);
        return builders.computeIfAbsent(key,
                k -> new BuilderState(name != null ? name : "StringBuilder@" + k));
    }

    /**
     * Analyse StringBuilder usage and return a report.
     */
    public StringBuilderReport analyze() {
        StringBuilderReport report = new StringBuilderReport();

        for (BuilderState state : builders.values()) {
            int mutators = state.mutatingThreads.size();
            int writes   = state.appendCount.get() + state.insertCount.get()
                         + state.deleteCount.get() + state.replaceCount.get();
            int reads    = state.readCount.get();
            int errors   = state.errorCount.get();

            if (writes == 0 && reads == 0) continue;

            report.totalBuilders++;

            if (mutators > 1) {
                report.sharedBuilderViolations.add(String.format(
                        "%s: mutated by %d threads (append: %d, insert: %d, delete: %d, replace: %d) — NOT THREAD SAFE!",
                        state.name, mutators,
                        state.appendCount.get(), state.insertCount.get(),
                        state.deleteCount.get(), state.replaceCount.get()));
            }

            if (errors > 0) {
                report.builderErrors.add(String.format(
                        "%s: %d exception(s) from concurrent access (possible StringIndexOutOfBoundsException)",
                        state.name, errors));
            }

            report.builderActivity.put(state.name, String.format(
                    "writes: %d from %d thread(s), reads: %d, errors: %d",
                    writes, mutators, reads, errors));
        }

        return report;
    }

    // ---- Report ----------------------------------------------------------------

    /**
     * Report produced by {@link #analyze()}.
     */
    public static class StringBuilderReport {

        int totalBuilders = 0;
        final java.util.List<String> sharedBuilderViolations = new java.util.ArrayList<>();
        final java.util.List<String> builderErrors           = new java.util.ArrayList<>();
        final Map<String, String>    builderActivity         = new ConcurrentHashMap<>();

        /** Returns {@code true} when shared-mutation or errors were detected. */
        public boolean hasIssues() {
            return !sharedBuilderViolations.isEmpty() || !builderErrors.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("STRING BUILDER ISSUES DETECTED:\n");

            if (!sharedBuilderViolations.isEmpty()) {
                sb.append("  Shared StringBuilder Mutations (NOT THREAD SAFE):\n");
                for (String v : sharedBuilderViolations) {
                    sb.append("    - ").append(v).append("\n");
                }
            }

            if (!builderErrors.isEmpty()) {
                sb.append("  Concurrent Access Errors:\n");
                for (String e : builderErrors) {
                    sb.append("    - ").append(e).append("\n");
                }
            }

            if (!builderActivity.isEmpty()) {
                sb.append("  Builder Activity:\n");
                for (Map.Entry<String, String> e : builderActivity.entrySet()) {
                    sb.append("    - ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("  Fix: use ThreadLocal<StringBuilder> or build strings locally per-thread and join at the end");
            return sb.toString();
        }
    }
}
