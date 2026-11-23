package se.deversity.asynctest.diagnostics;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

/**
 * A detector whose evidence belongs to one invocation round, and which therefore has to hear when
 * a round begins.
 *
 * <p>Rounds are ordered by the runner: every worker of round N has finished before round N+1
 * starts, so two accesses from different rounds cannot race. A detector that pairs or counts
 * events across threads closes the round in progress here, or a pair that straddles two rounds
 * is reported as a race that never happened (#698).
 *
 * <p>Implementing this is the whole registration: {@code AsyncTestContext} notifies every
 * detector of its run that implements it, so a new round-scoped detector cannot be left off a
 * list. {@code RoundStartFanOutIsCompleteTest} fails for a class that declares
 * {@code markInvocationStart()} without implementing this.
 *
 * @since 1.14.5
 */
@API(status = Status.INTERNAL, since = "1.14.5")
public interface RoundScoped {

    /**
     * Closes the round in progress. Called on the runner thread before each round, after the
     * previous round's workers have all finished, so nothing is legitimately in flight.
     */
    void markInvocationStart();
}
