package com.example.agentfixture;

/**
 * Safe publication through a volatile field assigned in a constructor, for #813 item 4.
 *
 * <p>The writer updates {@code data}, then constructs a {@link Flagged}, whose constructor sets
 * its volatile {@code ready}. A reader that sees {@code ready == true} is ordered after that write,
 * and so after the update of {@code data} that preceded it. The constructor's own write is not an
 * access the agent records, but its release is the edge.
 */
public class ConstructorPublicationBean {

    public int data;

    public Flagged published;

    /** A volatile flag set by the constructor, after the super constructor has run. */
    public static class Flagged {

        public volatile boolean ready;

        public Flagged() {
            ready = true;
        }
    }

    /** Updates the data, then constructs the flag that publishes it. */
    public void updateThenConstruct() {
        data = data + 1;
        published = new Flagged();
    }

    /** Reads the flag, and only when it was set updates the data. */
    public int bumpAfterReady() {
        Flagged flag = published;
        if (flag == null || !flag.ready) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    /** Updates the data having read no volatile field: nothing orders it after the writer. */
    public int bumpWithoutReady() {
        Flagged flag = published;
        if (flag == null) {
            return -1;
        }
        data = data + 1;
        return data;
    }
}
