package com.example.corpus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

/**
 * Refuses a {@code MUST_STAY_SILENT} row that never reaches the detector it names.
 *
 * <p>A silent row passes when its detector says nothing, and a row that never calls the detector
 * satisfies that for free. That is not a hypothetical: before #409 the silent half of
 * {@code CONCURRENT_MAP_CHECK_THEN_ACT} was three lines of Caffeine with no detector call in them,
 * so a detector that fired on every single {@code recordCheckThenAct} would have passed it. The
 * row was measuring nothing while reading as evidence, and nine silent rows now back a
 * {@code VERDICT} tier through {@code META-INF/async-test/verdict-evidence-corpus}.
 *
 * <p>{@code CorpusRecordingLaneTest.thePooledRowsPremiseHeld()} already does this properly for one
 * row, by asserting the conditions its silence depends on. This is the cheap general form of the
 * same idea: not "was the silence meaningful" - which is per-row and stays hand-written - but the
 * floor beneath it, that the detector was addressed at all.
 *
 * <p>It works on the lane's own source rather than at runtime, because a detector cannot generally
 * be asked how many observations it holds. The accessor names come from {@link AsyncTestContext}
 * by return type, so a rename moves them here too; a detector reached through more than one
 * accessor (the semaphore pair) satisfies the check through any of them.
 *
 * <p>{@code MUST_FIRE} rows need no such check. A row that fired reached its detector by
 * construction.
 */
final class SilentRowPremise {

    private static final Path SOURCE =
            Path.of("src/test/java/com/example/corpus/CorpusRecordingLaneTest.java");

    private SilentRowPremise() {
    }

    /** {@return every silent row that never addresses its own detector, with what was searched} */
    static List<String> rowsThatNeverReachTheirDetector() {
        String source = read();
        List<String> broken = new ArrayList<>();

        for (RecordingSubject subject : Corpus.recordingSubjects()) {
            if (subject.expectation() != RecordingSubject.Expectation.MUST_STAY_SILENT) {
                continue;
            }
            Set<String> accessors = accessorsFor(subject.detector());
            if (accessors.isEmpty()) {
                broken.add(subject.testMethod() + " names " + subject.detector()
                        + ", which no AsyncTestContext accessor returns, so this check cannot "
                        + "resolve what the body would have to call");
                continue;
            }
            String reachable = bodyWithHelpers(source, subject.testMethod());
            boolean reached = accessors.stream().anyMatch(name -> reachable.contains(name + "("));
            if (!reached) {
                broken.add(subject.testMethod() + " is the MUST_STAY_SILENT row for "
                        + subject.detector() + " and never calls it. Its silence is the absence of "
                        + "a call, not a decision, so a detector that fired on everything would "
                        + "pass this row. Looked for " + accessors + " in the body and in the "
                        + "helpers it calls");
            }
        }
        return broken;
    }

    /**
     * {@return the {@link AsyncTestContext} accessor names that hand out {@code type}'s detector}
     *
     * <p>Resolved by return type rather than by naming convention, because the convention does not
     * hold: {@code ExecutorShutdownDetector} is reached through {@code executorShutdownMonitor()}.
     *
     * @param type the detector a row names
     */
    private static Set<String> accessorsFor(DetectorType type) {
        String detectorClass = DetectorExposure.classOf(type);
        Set<String> names = new LinkedHashSet<>();
        for (Method method : AsyncTestContext.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())
                    && Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 0
                    && method.getReturnType().getSimpleName().equals(detectorClass)) {
                names.add(method.getName());
            }
        }
        return names;
    }

    /**
     * {@return {@code testMethod}'s body, plus the bodies of the lane's own methods it calls}
     *
     * <p>One level of indirection is followed and no more, which is what the lane actually uses:
     * {@code recorded_mutableIntKey_neverMutated} reaches its detector through {@code fileKeyOnce}
     * and would read as a violation without this. A helper that itself delegates further would
     * escape the check, and the answer to that is a deeper walk, not a looser one - filed if it
     * ever happens rather than built for a case the lane does not have.
     *
     * @param source     the lane's source
     * @param testMethod the row's test method
     */
    private static String bodyWithHelpers(String source, String testMethod) {
        String body = bodyOf(source, testMethod);
        StringBuilder reachable = new StringBuilder(body);
        Matcher calls = Pattern.compile("\\b([a-z][A-Za-z0-9_]*)\\s*\\(").matcher(body);
        Set<String> seen = new LinkedHashSet<>();
        while (calls.find()) {
            String name = calls.group(1);
            if (seen.add(name) && !name.equals(testMethod)) {
                reachable.append('\n').append(bodyOf(source, name));
            }
        }
        return reachable.toString();
    }

    /**
     * {@return the source text of {@code methodName}'s body, or empty if the lane has no such method}
     *
     * <p>Brace matching rather than a parser. The lane is one class of ordinary methods with no
     * string literal containing an unbalanced brace, and a parser dependency for this would be a
     * row in {@code docs/DEPENDENCIES.md} rather than a convenience.
     *
     * @param source     the lane's source
     * @param methodName the method to extract
     */
    private static String bodyOf(String source, String methodName) {
        Matcher declaration = Pattern.compile(
                "(?m)^\\s*(?:@\\w+\\s+)*(?:private|public|protected|static|final|void|[A-Za-z<>\\[\\],.?\\s]+?)\\b"
                        + Pattern.quote(methodName) + "\\s*\\([^)]*\\)\\s*\\{").matcher(source);
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
