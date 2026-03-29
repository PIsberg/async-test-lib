package com.github.asynctest.diagnostics;

/**
 * Interface for events that can be deduplicated by {@link IssueDeduplicator}.
 *
 * <p>Implementing classes must provide:
 * <ul>
 *   <li>A fingerprint that identifies similar issues (e.g., same field + line number)
 *   <li>The thread ID where the issue occurred
 *   <li>Location information (file/class and line number)
 * </ul>
 *
 * <p><strong>Example:</strong>
 * <pre>{@code
 * class RaceConditionEvent implements DeduplicatableEvent {
 *     private final String fieldName;
 *     private final int lineNumber;
 *     private final long threadId;
 *
 *     @Override
 *     public String getFingerprint() {
 *         // Same field + line = same issue
 *         return "RaceCondition:" + fieldName + ":" + lineNumber;
 *     }
 *
 *     @Override
 *     public long getThreadId() {
 *         return threadId;
 *     }
 *
 *     @Override
 *     public String getLocation() {
 *         return "MyClass." + fieldName;
 *     }
 *
 *     @Override
 *     public int getLineNumber() {
 *         return lineNumber;
 *     }
 * }
 * }</pre>
 *
 * @since 1.3.0
 */
public interface DeduplicatableEvent {

    /**
     * Get a fingerprint that identifies similar issues.
     *
     * <p>Events with the same fingerprint are considered duplicates and grouped together.
     * For example, race conditions on the same field at the same line should have
     * the same fingerprint, even if they occur on different threads.
     *
     * @return fingerprint string for grouping similar issues
     */
    String getFingerprint();

    /**
     * Get the ID of the thread where this issue occurred.
     *
     * @return thread ID
     */
    long getThreadId();

    /**
     * Get the location of this issue (e.g., class.field or method name).
     *
     * @return location string
     */
    String getLocation();

    /**
     * Get the line number where this issue occurred.
     *
     * @return line number, or -1 if unknown
     */
    int getLineNumber();

    /**
     * Get the type of this issue (e.g., "Race Condition", "False Sharing").
     *
     * @return issue type name
     */
    default String getType() {
        return getClass().getSimpleName().replace("Event", "");
    }
}
