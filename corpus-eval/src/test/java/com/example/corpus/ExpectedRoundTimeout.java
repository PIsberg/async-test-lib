package com.example.corpus;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

/**
 * Lets the one row whose subject never finishes pass on the runner's own timeout.
 *
 * <p>{@code LIVELOCKS} reports a body still running when its round timed out, so that row's run has
 * to time out, and a timed-out run fails. Only the runner's marker type is let through: the message
 * is a test author's to write, so a body's own assertion that mentions a timeout still fails the row.
 * The finding is then held to the row like any other by {@link CorpusGates}.
 */
final class ExpectedRoundTimeout implements TestExecutionExceptionHandler {

    /** The simple name of the runner's package-private marker for a round that timed out. */
    private static final String RUNNER_TIMEOUT = "RoundTimeoutError";

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable thrown) throws Throwable {
        if (!RUNNER_TIMEOUT.equals(thrown.getClass().getSimpleName())) {
            throw thrown;
        }
    }
}
