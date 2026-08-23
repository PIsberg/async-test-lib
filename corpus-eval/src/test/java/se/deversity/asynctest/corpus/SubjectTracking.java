package se.deversity.asynctest.corpus;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Names the subject under test while it runs, so {@link CorpusRecorder} can attribute findings.
 *
 * <p>JUnit runs these callbacks around the invocation interceptor that owns the whole N x M run,
 * so every violation the runner reports for a subject is fired inside this window.
 */
final class SubjectTracking implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        CorpusRecorder.currentSubject(context.getRequiredTestMethod().getName());
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        CorpusRecorder.currentSubject("unattributed");
    }
}
