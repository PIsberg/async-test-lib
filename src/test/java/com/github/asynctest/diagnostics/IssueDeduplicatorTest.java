package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link IssueDeduplicator}.
 */
class IssueDeduplicatorTest {

    // Test event implementation
    private static class TestEvent implements DeduplicatableEvent {
        private final String type;
        private final String location;
        private final int lineNumber;
        private final long threadId;

        TestEvent(String type, String location, int lineNumber, long threadId) {
            this.type = type;
            this.location = location;
            this.lineNumber = lineNumber;
            this.threadId = threadId;
        }

        @Override
        public String getFingerprint() {
            return type + ":" + location + ":" + lineNumber;
        }

        @Override
        public long getThreadId() {
            return threadId;
        }

        @Override
        public String getLocation() {
            return location;
        }

        @Override
        public int getLineNumber() {
            return lineNumber;
        }

        @Override
        public String getType() {
            return type;
        }
    }

    @Test
    void singleEvent_noDeduplication() {
        IssueDeduplicator<TestEvent> dedup = new IssueDeduplicator<>();
        dedup.record(new TestEvent("Race", "field1", 10, 1));

        assertEquals(1, dedup.getTotalCount());
        assertEquals(1, dedup.getUniqueCount());
        assertTrue(dedup.hasIssues());

        List<IssueDeduplicator.IssueGroup<TestEvent>> groups = dedup.getGroups();
        assertEquals(1, groups.size());
        assertEquals(1, groups.get(0).getCount());
    }

    @Test
    void multipleThreads_SameIssue_deduplicates() {
        IssueDeduplicator<TestEvent> dedup = new IssueDeduplicator<>();

        // 100 threads hit the same race condition
        for (int i = 1; i <= 100; i++) {
            dedup.record(new TestEvent("Race", "BankAccount.balance", 23, i));
        }

        assertEquals(100, dedup.getTotalCount());
        assertEquals(1, dedup.getUniqueCount(), "Should deduplicate to 1 unique issue");

        List<IssueDeduplicator.IssueGroup<TestEvent>> groups = dedup.getGroups();
        assertEquals(1, groups.size());

        IssueDeduplicator.IssueGroup<TestEvent> group = groups.get(0);
        assertEquals(100, group.getCount());
        assertEquals(100, group.getAffectedThreadCount());
    }

    @Test
    void differentIssues_notDeduplicated() {
        IssueDeduplicator<TestEvent> dedup = new IssueDeduplicator<>();

        // Different fields = different issues
        dedup.record(new TestEvent("Race", "field1", 10, 1));
        dedup.record(new TestEvent("Race", "field2", 20, 2));
        dedup.record(new TestEvent("Race", "field3", 30, 3));

        assertEquals(3, dedup.getTotalCount());
        assertEquals(3, dedup.getUniqueCount());
    }

    @Test
    void sameField_differentLines_notDeduplicated() {
        IssueDeduplicator<TestEvent> dedup = new IssueDeduplicator<>();

        // Same field but different lines = different issues
        dedup.record(new TestEvent("Race", "balance", 10, 1));
        dedup.record(new TestEvent("Race", "balance", 20, 2));

        assertEquals(2, dedup.getTotalCount());
        assertEquals(2, dedup.getUniqueCount());
    }

    @Test
    void formatSummary_includesSuppressionInfo() {
        IssueDeduplicator<TestEvent> dedup = new IssueDeduplicator<>();

        // 10 threads hit the same issue
        for (int i = 1; i <= 10; i++) {
            dedup.record(new TestEvent("Race", "counter", 5, i));
        }

        String summary = dedup.formatSummary("Race Condition");

        assertTrue(summary.contains("1 unique issue"));
        assertTrue(summary.contains("10 total occurrences"));
        assertTrue(summary.contains("suppressed"));
    }

    @Test
    void issueGroup_formatDetailed_showsThreadSummary() {
        IssueDeduplicator<TestEvent> dedup = new IssueDeduplicator<>();

        // Add 20 threads
        for (int i = 1; i <= 20; i++) {
            dedup.record(new TestEvent("Race", "field", 10, i));
        }

        List<IssueDeduplicator.IssueGroup<TestEvent>> groups = dedup.getGroups();
        String detailed = groups.get(0).formatDetailed();

        assertTrue(detailed.contains("Location: field"));
        assertTrue(detailed.contains("Occurrences: 20"));
        assertTrue(detailed.contains("affected threads"));
        assertTrue(detailed.contains("19 similar issue(s) suppressed"));
    }

    @Test
    void issueGroup_formatDetailed_limitsThreadList() {
        IssueDeduplicator<TestEvent> dedup = new IssueDeduplicator<>();

        // Add 100 threads - should summarize
        for (int i = 1; i <= 100; i++) {
            dedup.record(new TestEvent("Race", "field", 10, i));
        }

        List<IssueDeduplicator.IssueGroup<TestEvent>> groups = dedup.getGroups();
        String detailed = groups.get(0).formatDetailed();

        // Should show first 5 and summarize the rest
        assertTrue(detailed.contains("... (95 more)"));
    }

    @Test
    void clear_resetsAllData() {
        IssueDeduplicator<TestEvent> dedup = new IssueDeduplicator<>();

        dedup.record(new TestEvent("Race", "field", 10, 1));
        dedup.record(new TestEvent("Race", "field", 10, 2));

        dedup.clear();

        assertEquals(0, dedup.getTotalCount());
        assertEquals(0, dedup.getUniqueCount());
        assertFalse(dedup.hasIssues());
    }

    @Test
    void getGroups_sortedByCount() {
        IssueDeduplicator<TestEvent> dedup = new IssueDeduplicator<>();

        // Issue A: 50 occurrences
        for (int i = 1; i <= 50; i++) {
            dedup.record(new TestEvent("Race", "fieldA", 10, i));
        }

        // Issue B: 10 occurrences
        for (int i = 1; i <= 10; i++) {
            dedup.record(new TestEvent("Race", "fieldB", 20, i + 100));
        }

        // Issue C: 5 occurrences
        for (int i = 1; i <= 5; i++) {
            dedup.record(new TestEvent("Race", "fieldC", 30, i + 200));
        }

        List<IssueDeduplicator.IssueGroup<TestEvent>> groups = dedup.getGroups();

        assertEquals(3, groups.size());
        assertEquals(50, groups.get(0).getCount(), "First should be most frequent");
        assertEquals(10, groups.get(1).getCount());
        assertEquals(5, groups.get(2).getCount());
    }

    @Test
    void issueGroup_getAffectedThreadIds_returnsUniqueIds() {
        IssueDeduplicator<TestEvent> dedup = new IssueDeduplicator<>();

        // Same thread hits issue multiple times
        dedup.record(new TestEvent("Race", "field", 10, 1));
        dedup.record(new TestEvent("Race", "field", 10, 1));
        dedup.record(new TestEvent("Race", "field", 10, 1));

        List<IssueDeduplicator.IssueGroup<TestEvent>> groups = dedup.getGroups();
        assertEquals(3, groups.get(0).getCount());
        assertEquals(1, groups.get(0).getAffectedThreadCount(), "Same thread = 1 unique");
    }

    @Test
    void issueGroup_getFirstEvent_returnsRepresentative() {
        IssueDeduplicator<TestEvent> dedup = new IssueDeduplicator<>();

        TestEvent original = new TestEvent("Race", "field", 10, 1);
        dedup.record(original);
        dedup.record(new TestEvent("Race", "field", 10, 2));

        List<IssueDeduplicator.IssueGroup<TestEvent>> groups = dedup.getGroups();
        TestEvent first = groups.get(0).getFirstEvent();

        assertNotNull(first);
        assertEquals("field", first.getLocation());
    }

    @Test
    void formatSummary_noIssues_returnsMessage() {
        IssueDeduplicator<TestEvent> dedup = new IssueDeduplicator<>();

        String summary = dedup.formatSummary("Race Condition");

        assertTrue(summary.contains("No issues detected"));
    }
}
