package com.example.corpus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Holds the idiom lane's bodies to recording nothing, except where a row says why it must.
 *
 * <p>The lane's claim is that a correct idiom, written the way a user writes it, draws nothing
 * worth failing a build on. A body that records has stopped being the way a user writes it, and
 * a body that records to the detector its row names can make that detector say anything. So the
 * rule is {@link AgentRowPremise}'s, with one written exception: a row named in
 * {@link Corpus#idiomManualApiRows()}, whose idiom no woven call site can show a detector, may
 * call the manual API inside its own body. It is checked in both directions, because an entry
 * whose body no longer records is a reason nobody needs any more.
 */
final class IdiomRowPremise {

    private static final Path SOURCE =
            Path.of("src/test/java/com/example/corpus/CorpusIdiomLaneTest.java");

    private IdiomRowPremise() {
    }

    /** {@return every line outside a named manual-API row's body that touches the recording API} */
    static List<String> linesThatRecordWithoutAReason(String source, Map<String, String> manualRows) {
        String rest = source;
        for (String row : manualRows.keySet()) {
            String body = AgentRowPremise.bodyOf(rest, row);
            if (!body.isEmpty()) {
                rest = rest.replace(body, body.replaceAll("[^\n]", " "));
            }
        }
        // The import is not a call; the manual-API rows need it, and it names nothing a body did.
        return AgentRowPremise.linesThatRecord(rest).stream()
                .filter(line -> !line.contains(": import "))
                .toList();
    }

    /** {@return every named manual-API row whose body does not touch the recording API} */
    static List<String> manualRowsThatRecordNothing(String source, Map<String, String> manualRows) {
        List<String> stale = new ArrayList<>();
        for (String row : manualRows.keySet()) {
            if (AgentRowPremise.linesThatRecord(AgentRowPremise.bodyOf(source, row)).isEmpty()) {
                stale.add(row);
            }
        }
        return stale;
    }

    static String read() {
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
