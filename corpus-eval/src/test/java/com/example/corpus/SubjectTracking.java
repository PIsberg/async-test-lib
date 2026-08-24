package com.example.corpus;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Names the subject under test while it runs, so {@link CorpusRecorder} can attribute findings.
 *
 * <p>JUnit runs these callbacks around the invocation interceptor that owns the whole N x M run,
 * so every violation the runner reports for a subject is fired inside this window, and the
 * telemetry counter's movement across it is the evidence that subject produced. Subjects run
 * sequentially in this module, which is what makes that delta attributable.
 */
final class SubjectTracking implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        CorpusRecorder.currentSubject(context.getRequiredTestMethod().getName());
        CorpusRecorder.markSubjectStart();
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        CorpusRecorder.markSubjectEnd(context.getRequiredTestMethod().getName());
        CorpusRecorder.currentSubject("unattributed");
    }
}
