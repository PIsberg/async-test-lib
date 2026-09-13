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
 * <p>A third lane runs {@link CorpusRecordingLaneTest} over the same libraries with bodies that
 * do cooperate. It is a different measurement over a different denominator and must never be
 * merged into the other two: its subjects are still unmodified third-party classes, but the test
 * body calls the recording API the way a user following {@code AsyncTestContext} would, which is
 * exactly what the other lanes refuse to do. Without it, 125 of the 146 detectors have an
 * exposure of zero in every lane and "no false positive from detector X" and "X never ran" stay
 * the same row for 86% of the roster.
 *
 * <p>Surefire sets {@code corpus.lane} per execution; a plain {@code mvn test} runs all three.
 */
enum CorpusLane {

    /** The agent attached as {@code fields=true,collections=true}. */
    AGENT_ON("agent-on", "corpus-eval.md"),

    /** Nothing attached, nothing recorded: only the JVM and the harness can feed a detector. */
    AGENT_OFF("agent-off", "corpus-eval-agent-off.md"),

    /**
     * Nothing attached, and the body records what it did.
     *
     * <p>The agent stays detached on purpose. With both feeds live a finding could have come
     * from either, and the point of this lane is that every finding in it is attributable to a
     * {@code record*} call the test made - which is what lets its assertions be structural
     * rather than probabilistic.
     *
     * <p>That attributability is also why this lane, and only this lane, holds its silent rows to
     * an absolute collateral bar. See {@link #failsOnAnyCollateral()}.
     */
    RECORDING("recording", "corpus-eval-recording.md"),

    /**
     * The agent attached, and the body records nothing at all.
     *
     * <p>The recording lane cannot reach the agent-fed detectors: their input is a call site the
     * weaver substitutes, and no {@code record*} API exists to stand in for it. This lane pairs
     * them the only way that is left, by writing the call itself. A must-fire row shares one real
     * {@code SimpleDateFormat} across the threads; its twin gives each thread its own. The
     * difference between the two rows is a field declaration, and everything the detector sees in
     * between comes from the agent rewriting {@code format} at the call site.
     *
     * <p>The body making no {@code record*} call is the whole premise, so it is a gate rather than
     * a convention: {@link AgentRowPremise} fails the lane if a body in it touches the recording
     * API. That is the inverse of {@link SilentRowPremise} on the recording lane, and for the same
     * reason. There, a silent row that reaches no detector proves nothing; here, a firing row that
     * recorded its own finding proves nothing about the agent.
     *
     * <p>Its silent rows keep the tier bar rather than the recording lane's absolute one, for a
     * reason that follows from the premise above. See {@link #failsOnAnyCollateral()}.
     */
    AGENT_PAIRS("agent-pairs", "corpus-eval-agent-pairs.md"),

    /**
     * The agent-pair lane's library rows again, with those libraries excluded from weaving.
     *
     * <p>A library pair's claim is that its finding came from a call inside Guava, Jackson or
     * HikariCP, not from anything in the test file. Its body calls no woven JDK method, but that
     * is a property of how it was written, and nothing re-checked it: a later edit that put a
     * {@code countDown()} or a {@code release()} into a firing body would keep passing and keep
     * being counted by {@link LibraryReach}, while the finding came from the test (#544).
     *
     * <p>So the same rows run here with every library package excluded from the agent. The JDK
     * call sites inside those libraries are no longer substituted, and each firing row must
     * therefore go silent: a finding that survives came from outside the library. Only library
     * rows run, which keeps the lane to about half of the agent-pair lane's time.
     */
    AGENT_PAIRS_LIBRARY_EXCLUDED("agent-pairs-library-excluded",
            "corpus-eval-agent-pairs-library-excluded.md");

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

    /**
     * {@return whether a silent row here fails on collateral from any detector, at any tier}
     *
     * <p>This is the one gate that means two different things in two lanes, so the asymmetry is
     * stated here rather than inside {@code CorpusGates}, where only a reader of that file would
     * find it. Both lanes assert the same idea - a {@code MUST_STAY_SILENT} body is this module
     * writing down that a use is correct, so a finding from a detector other than the row's own is
     * a claim against code the corpus vouches for. They differ in how much of that claim the row
     * is entitled to refuse.
     *
     * <p><strong>The recording lane: absolute.</strong> Its bodies are written here, line by line,
     * to make exactly the {@code record*} calls the row is about. Nothing else in such a body is
     * incidental, because there is nothing else in it: no scaffolding a detector could legitimately
     * remark on, and no second feed a finding could have arrived through. A finding from another
     * detector is therefore either that detector being wrong about correct code, or this row's
     * rationale being wrong about what it wrote - and both are defects the module exists to catch,
     * at PROMPT tier as much as at VERDICT. All 119 of its silent rows already clear this bar with
     * room to spare: the lane's whole run produces 117 findings for 117 must-fire rows and nothing
     * else, so the ratchet costs nothing today and refuses the first regression that would.
     *
     * <p><strong>The agent-pair lane: VERDICT at HIGH or CRITICAL.</strong> Here the body cannot be
     * only what the row is about. {@link AgentRowPremise} requires the silent half to go through
     * the same substituted call sites as the twin it is paired with, which drags in real
     * scaffolding the row never claimed anything about:
     * {@code agent_deadlock_noThreadBlockedOnAnother} holds two nested monitors across a
     * {@code Thread.sleep} because its firing twin must, and {@code SleepInLockDetector} is right
     * to remark on that. A row that says "no deadlock here" has not said "and nothing else in this
     * body is worth a word". So the bar is the one {@link CorpusReport#isFalsePositive} uses for
     * documented-safe code: the tier and severity at which the library stops asking a question and
     * claims the code is wrong.
     *
     * <p>The bar is a property of how a lane's bodies are written, not of how good its detectors
     * are, which is why it belongs on the lane. A new lane picks its answer from that same
     * question: can a body here contain anything the row is not making a claim about?
     */
    boolean failsOnAnyCollateral() {
        return this == RECORDING;
    }

    /** {@return whether the agent is attached with -javaagent in this lane} */
    boolean attachesTheAgent() {
        return this == AGENT_ON || this == AGENT_PAIRS || this == AGENT_PAIRS_LIBRARY_EXCLUDED;
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
                + "one of " + java.util.Arrays.stream(values())
                        .map(lane -> lane.propertyValue).toList());
    }
}
