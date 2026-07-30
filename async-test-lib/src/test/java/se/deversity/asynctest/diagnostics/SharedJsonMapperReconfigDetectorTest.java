package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SharedJsonMapperReconfigDetectorTest {

    /** Stand-in for a mapper/serializer instance (e.g. an ObjectMapper or Gson). */
    private static final class FakeMapper {
    }

    @Test
    void cleanWhenNoActivity() {
        var d = new SharedJsonMapperReconfigDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void configureBeforeUseIsNotFlagged() {
        var d = new SharedJsonMapperReconfigDetector();
        var mapper = new FakeMapper();

        d.recordConfigMutation(mapper, "registerModule(JavaTimeModule)");
        d.recordConfigMutation(mapper, "setSerializationInclusion(NON_NULL)");
        d.recordUse(mapper);
        d.recordUse(mapper);

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void mutationAfterUseBySameSingleThreadIsNotFlagged() {
        var d = new SharedJsonMapperReconfigDetector();
        var mapper = new FakeMapper();

        d.recordUse(mapper);
        d.recordConfigMutation(mapper, "registerModule(JavaTimeModule)");
        d.recordUse(mapper);

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void mutationFromNonUsingThreadAfterUseIsFlagged() throws Exception {
        var d = new SharedJsonMapperReconfigDetector();
        var mapper = new FakeMapper();

        d.recordUse(mapper);
        Thread mutator = new Thread(() -> d.recordConfigMutation(mapper, "registerModule(JavaTimeModule)"));
        mutator.start();
        mutator.join();

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains(FakeMapper.class.getName()));
        assertTrue(msg.contains("registerModule(JavaTimeModule)"));
        assertEquals(1, report.structuredViolations.size());
        var violation = report.structuredViolations.get(0);
        assertEquals("SharedJsonMapperReconfig", violation.detector());
        assertEquals(IssueSeverity.HIGH, violation.severity());
        assertEquals(FakeMapper.class.getName(), violation.attributes().get("className"));
    }

    @Test
    void mutationAfterConcurrentUseFromMultipleThreadsIsFlagged() throws Exception {
        var d = new SharedJsonMapperReconfigDetector();
        var mapper = new FakeMapper();

        d.recordUse(mapper);
        Thread user = new Thread(() -> d.recordUse(mapper));
        user.start();
        user.join();

        // Mutation now comes from one of the two using threads (the main thread),
        // but the instance has already been used from two distinct threads.
        d.recordConfigMutation(mapper, "setSerializationInclusion(NON_NULL)");

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("2 thread(s)"));
        assertEquals(2, report.structuredViolations.get(0).attributes().get("usingThreadCount"));
    }

    @Test
    void distinctInstancesAreTrackedSeparately() throws Exception {
        var d = new SharedJsonMapperReconfigDetector();
        var a = new FakeMapper();
        var b = new FakeMapper();

        d.recordUse(a);
        d.recordUse(b);

        Thread t = new Thread(() -> d.recordConfigMutation(a, "configure(FAIL_ON_UNKNOWN, false)"));
        t.start();
        t.join();

        var report = d.analyze();
        assertEquals(1, report.violations.size());
        assertTrue(report.violations.get(0).contains("configure(FAIL_ON_UNKNOWN, false)"));
    }

    @Test
    void nullsAreIgnored() {
        var d = new SharedJsonMapperReconfigDetector();
        d.recordUse(null);
        d.recordConfigMutation(null, "some-mutation");
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void nullMutationDescriptionGetsFallbackLabel() throws Exception {
        var d = new SharedJsonMapperReconfigDetector();
        var mapper = new FakeMapper();

        d.recordUse(mapper);
        Thread mutator = new Thread(() -> d.recordConfigMutation(mapper, null));
        mutator.start();
        mutator.join();

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("configuration change"));
    }

    @Test
    void reportDescribesHazardAndFix() throws Exception {
        var d = new SharedJsonMapperReconfigDetector();
        var mapper = new FakeMapper();

        d.recordUse(mapper);
        Thread mutator = new Thread(() -> d.recordConfigMutation(mapper, "registerModule(JavaTimeModule)"));
        mutator.start();
        mutator.join();

        String describe = d.analyze().toString();
        assertTrue(describe.contains("SHARED JSON MAPPER RECONFIG DETECTED"));
        assertTrue(describe.contains("not safe once a serializer/mapper is visible to other threads"));
        assertTrue(describe.contains("ConcurrentModificationException"));
        assertTrue(describe.contains("Freeze mapper/builder configuration"));
        assertTrue(describe.contains("ObjectMapper.copy()"));
        assertTrue(describe.contains("Gson"));
    }

    @Test
    void analyzeIsIdempotent() throws Exception {
        var d = new SharedJsonMapperReconfigDetector();
        var mapper = new FakeMapper();

        d.recordUse(mapper);
        Thread mutator = new Thread(() -> d.recordConfigMutation(mapper, "registerModule(JavaTimeModule)"));
        mutator.start();
        mutator.join();

        var first = d.analyze();
        var second = d.analyze();

        assertEquals(first.violations, second.violations);
        assertEquals(first.structuredViolations.size(), second.structuredViolations.size());
        assertEquals(
                first.structuredViolations.get(0).message(),
                second.structuredViolations.get(0).message());
    }
}
