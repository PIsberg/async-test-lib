package com.example.corpus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Refuses an agent-lane row that recorded its own finding.
 *
 * <p>The agent-pair lane exists to measure one thing: whether the woven call sites, on their own,
 * let a detector tell a shared instance from a confined one. That claim survives exactly as long
 * as the bodies stay silent. One {@code AsyncTestContext.sharedInstanceMonitor().record(...)} in a
 * MUST_FIRE row and the row still passes, still reads as evidence, and no longer says anything
 * about the agent - it says the test can call a method.
 *
 * <p>This is {@link SilentRowPremise} pointed the other way. There, a silent row that never
 * reached its detector was silence by omission; here, a firing row that fed its own detector is a
 * finding by construction. Both are the same failure: a row whose result does not depend on the
 * thing under measurement.
 *
 * <p>The check is deliberately blunt. It does not look for the accessor a given row would have to
 * call, it refuses the whole recording API anywhere in the lane, because there is no legitimate
 * use of it here at all. Comments are stripped first so that prose explaining why a body does not
 * record cannot fail the gate that enforces it.
 */
final class AgentRowPremise {

    private static final Path SOURCE =
            Path.of("src/test/java/com/example/corpus/CorpusAgentPairLaneTest.java");

    /**
     * The one type every recording call goes through.
     *
     * <p>Every {@code record*} and {@code register*} entry point in the library is reached from a
     * static accessor on {@code AsyncTestContext}, so naming the class covers the API without
     * having to enumerate 146 detectors' worth of method names, and keeps covering it when one is
     * added.
     */
    private static final String RECORDING_API = "AsyncTestContext";

    private AgentRowPremise() {
    }

    /** {@return every line of the agent lane that touches the recording API, with its number} */
    static List<String> linesThatRecord() {
        String[] lines = withoutComments(read()).split("\n", -1);
        List<String> offenders = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(RECORDING_API)) {
                offenders.add((i + 1) + ": " + lines[i].strip());
            }
        }
        return offenders;
    }

    /**
     * {@return every pair whose silent row does not make all the calls its firing row makes}
     *
     * <p>The lane has no {@link SilentRowPremise} to lean on. There, a silent row's premise is
     * that it called the detector, and the accessor name makes that checkable. Here there is no
     * call to look for: the row's input is a JDK method the weaver rewrote, and the row is silent
     * for the right reason only if it went through the same rewritten methods as its twin and the
     * detector still separated them.
     *
     * <p>So the check is on the pair rather than the row. Every method a MUST_FIRE body calls, its
     * MUST_STAY_SILENT twin must call too; the twin may call more (it has an instance to build).
     * That is the difference between "confining the instance silenced the detector" and "this row
     * stopped exercising the call site", which is the failure that turns a green pair into
     * decoration.
     *
     * <p>What it does not catch: the overload. {@code append(String)} is substituted and
     * {@code append(char)} is not, and both read as {@code append} here, so a pair can satisfy
     * this and still be measuring nothing - which is exactly how the first draft of the
     * StringBuilder and Formatter rows failed, silently and in the MUST_FIRE direction. The
     * MUST_FIRE half is what catches that, by having to actually fire.
     */
    static List<String> pairsWhoseSilentRowDropsACall() {
        String source = read();
        List<String> broken = new ArrayList<>();

        for (RecordingSubject loud : Corpus.subjectsFor(CorpusLane.AGENT_PAIRS)) {
            if (loud.expectation() != RecordingSubject.Expectation.MUST_FIRE) {
                continue;
            }
            RecordingSubject quiet = Corpus.subjectsFor(CorpusLane.AGENT_PAIRS).stream()
                    .filter(s -> s.detector() == loud.detector())
                    .filter(s -> s.expectation() == RecordingSubject.Expectation.MUST_STAY_SILENT)
                    .findFirst()
                    .orElse(null);
            if (quiet == null) {
                broken.add(loud.testMethod() + " fires for " + loud.detector()
                        + " with no MUST_STAY_SILENT twin, so nothing says the detector is "
                        + "reporting the sharing rather than the type");
                continue;
            }
            Set<String> loudCalls = callsIn(bodyOf(source, loud.testMethod()));
            Set<String> quietCalls = callsIn(bodyOf(source, quiet.testMethod()));
            Set<String> dropped = new LinkedHashSet<>(loudCalls);
            dropped.removeAll(quietCalls);
            if (!dropped.isEmpty()) {
                broken.add(quiet.testMethod() + " is the silent half of " + loud.detector()
                        + " and does not call " + dropped + ", which " + loud.testMethod()
                        + " does. Its silence may be the missing call rather than the confinement");
            }
        }
        return broken;
    }

    /**
     * {@return the method names invoked on a receiver in {@code body}}
     *
     * <p>Receiver calls only, so that {@code new StringBuilder()} does not read as a call named
     * {@code StringBuilder} and split a pair that is doing the right thing.
     *
     * @param body a test body's source text
     */
    private static Set<String> callsIn(String body) {
        Set<String> names = new LinkedHashSet<>();
        Matcher calls = Pattern.compile("\\.([a-zA-Z][A-Za-z0-9_]*)\\s*\\(").matcher(body);
        while (calls.find()) {
            names.add(calls.group(1));
        }
        return names;
    }

    /**
     * {@return the source text of {@code methodName}'s body}
     *
     * <p>Brace matching, for the reason {@link SilentRowPremise} gives: this lane is one class of
     * ordinary methods, and a parser for it would be a row in {@code docs/DEPENDENCIES.md} rather
     * than a convenience.
     *
     * @param source     the lane's source
     * @param methodName the row's test method
     */
    private static String bodyOf(String source, String methodName) {
        Matcher declaration = Pattern.compile(
                "(?m)^\\s*(?:private\\s+)?(?:static\\s+)?void\\s+"
                        + Pattern.quote(methodName) + "\\s*\\(\\)\\s*\\{").matcher(source);
        if (!declaration.find()) {
            return "";
        }
        int open = source.indexOf('{', declaration.start());
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        return source.substring(open);
    }

    /**
     * {@return {@code source} with block and line comments blanked out, line numbering preserved}
     *
     * <p>Newlines inside a stripped block comment are kept so that a reported line number still
     * points at the offending line in the file. String literals are not tracked: this lane has no
     * literal containing a comment opener, and a lexer for it would be a dependency rather than a
     * convenience.
     *
     * @param source the lane's source
     */
    private static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                int stop = end < 0 ? source.length() : end + 2;
                for (int j = i; j < stop; j++) {
                    out.append(source.charAt(j) == '\n' ? '\n' : ' ');
                }
                i = stop;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                int stop = end < 0 ? source.length() : end;
                out.append(" ".repeat(stop - i));
                i = stop;
            } else {
                out.append(source.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    private static String read() {
        try {
            return Files.readString(SOURCE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not read " + SOURCE.toAbsolutePath() + ". This gate reads the lane's "
                            + "own source, so it depends on the module directory being the working "
                            + "directory, which is how Surefire runs it", e);
        }
    }
}
