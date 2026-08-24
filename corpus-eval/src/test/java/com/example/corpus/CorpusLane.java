package com.example.corpus;

/**
 * Which of the two runs of the same corpus this JVM is.
 *
 * <p>The module runs {@link CorpusEvalTest} twice: once with the agent attached, which is the
 * configuration every number in the write-up comes from, and once with nothing attached at all.
 * The second run is a control rather than a second measurement. The exposure table claims that
 * without the agent the two agent-fed detectors have no input, so a run that records nothing must
 * observe nothing from them, and {@link CorpusGates} asserts exactly that. A finding there would
 * mean the feed classification is wrong, not that the code under test got worse.
 *
 * <p>Surefire sets {@code corpus.lane} per execution; a plain {@code mvn test} runs both.
 */
enum CorpusLane {

    /** The agent attached as {@code fields=true,collections=true}. */
    AGENT_ON("agent-on", "corpus-eval.md"),

    /** Nothing attached, nothing recorded: only the JVM and the harness can feed a detector. */
    AGENT_OFF("agent-off", "corpus-eval-agent-off.md");

    private final String propertyValue;
    private final String reportFile;

    CorpusLane(String propertyValue, String reportFile) {
        this.propertyValue = propertyValue;
        this.reportFile = reportFile;
    }

    /** {@return the value of {@code -Dcorpus.lane} that selects this lane} */
    String propertyValue() {
        return propertyValue;
    }

    /** {@return the file this lane writes its report to, under {@code target/corpus-eval}} */
    String reportFile() {
        return reportFile;
    }

    /** {@return the lane this JVM is running, defaulting to the attached one} */
    static CorpusLane current() {
        String configured = System.getProperty("corpus.lane", AGENT_ON.propertyValue);
        for (CorpusLane lane : values()) {
            if (lane.propertyValue.equals(configured)) {
                return lane;
            }
        }
        throw new IllegalStateException("-Dcorpus.lane=" + configured + " names no lane; expected "
                + AGENT_ON.propertyValue + " or " + AGENT_OFF.propertyValue);
    }
}
