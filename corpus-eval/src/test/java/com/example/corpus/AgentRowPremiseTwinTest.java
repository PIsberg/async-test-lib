package com.example.corpus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins which silent row {@link AgentRowPremise} compares a firing row against.
 *
 * <p>The premise gate is only as good as the pairing under it. Before a detector could have two
 * pairs in the agent lane, the twin was the first silent row naming the detector; with a JDK pair
 * and a library pair for the same detector, that would compare a Guava Monitor body against
 * a ReentrantLock body and report a dropped call that neither pair had dropped. These cases are
 * the three shapes the lookup has to get right.
 */
class AgentRowPremiseTwinTest {

    @Test
    @DisplayName("a library row pairs with its own library's silent row, not the JDK one")
    void aLibraryRowPairsWithinItsClass() {
        assertEquals("agent_guavaMonitorEnter_leftInFinally",
                twinOf("agent_guavaMonitorEnter_neverLeft"));
        assertEquals("agent_lock_releasedInFinally",
                twinOf("agent_lock_acquiredAndNeverReleased"));
    }

    @Test
    @DisplayName("two pairs of one class for one detector pair in declaration order")
    void backToBackPairsResolveToTheFollowingRow() {
        assertEquals("agent_blockingQueue_drainedAsItFilled",
                twinOf("agent_blockingQueue_filledToCapacity"));
        assertEquals("agent_blockingQueue_discardedOfferAccepted",
                twinOf("agent_blockingQueue_rejectionDiscarded"));
    }

    @Test
    @DisplayName("a silent row declared before its firing twin is still found")
    void aSilentRowDeclaredFirstIsFound() {
        assertEquals("agent_deadlock_noThreadBlockedOnAnother",
                twinOf("agent_deadlock_twoThreadsBlockedOnEachOther"));
    }

    private static String twinOf(String loud) {
        RecordingSubject row = Corpus.pairByTestMethod(CorpusLane.AGENT_PAIRS, loud);
        RecordingSubject twin = AgentRowPremise.twinOf(row);
        return twin == null ? null : twin.testMethod();
    }
}
