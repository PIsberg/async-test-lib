package com.example.corpus;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Names the subject under test while it runs, so {@link CorpusRecorder} can attribute findings.
 *
 * <p>JUnit runs these callbacks around the invocation interceptor that owns the whole N x M run and
 * around the test's {@code @AfterEach} methods, after which the runner reports an executor teardown
 * did not close (#996), so every violation the runner reports for a subject is fired inside this
 * window, and the telemetry counter's movement across it is the evidence that subject produced.
 * Subjects run sequentially in this module, which is what makes that delta attributable.
 */
final class SubjectTracking implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        CorpusRecorder.currentSubject(context.getRequiredTestMethod().getName());
        CorpusRecorder.markSubjectStart();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        CorpusRecorder.markSubjectEnd(context.getRequiredTestMethod().getName());
        CorpusRecorder.currentSubject("unattributed");
    }
}
