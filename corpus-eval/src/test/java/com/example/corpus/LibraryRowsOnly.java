package com.example.corpus;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Method;

/**
 * Runs only the library rows of the agent-pair lane when the libraries are excluded from weaving.
 *
 * <p>A JDK row calls its JDK type from the test file, so excluding Guava, Jackson and HikariCP
 * from the agent says nothing about where its finding came from, and running it again would only
 * cost time. In every other lane this condition enables everything.
 */
final class LibraryRowsOnly implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (CorpusLane.current() != CorpusLane.AGENT_PAIRS_LIBRARY_EXCLUDED) {
            return ConditionEvaluationResult.enabled("not the library-exclusion lane");
        }
        Method method = context.getTestMethod().orElse(null);
        if (method == null) {
            return ConditionEvaluationResult.enabled("class-level evaluation");
        }
        RecordingSubject row = Corpus.pairByTestMethod(
                CorpusLane.AGENT_PAIRS_LIBRARY_EXCLUDED, method.getName());
        return row != null
                ? ConditionEvaluationResult.enabled("a library row")
                : ConditionEvaluationResult.disabled("a JDK row, which this lane has nothing to say about");
    }
}
