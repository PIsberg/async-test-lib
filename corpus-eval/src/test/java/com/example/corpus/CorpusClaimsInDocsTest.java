package com.example.corpus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the corpus numbers printed in prose to the corpus that produced them.
 *
 * <p>Every count in {@code README.md} and {@code docs/analysis/corpus-eval.md} is a claim about
 * this module, and until now nothing checked one. The claims went stale the same afternoon the
 * corpus grew: the README said 42 subjects, 20 of 20 and 0 of 22 while the module measured 82, 22
 * of 22 and 0 of 60, and its own agent bullet said 21 in the same breath as the evidence section
 * said 5. A reader evaluating the tool reads those two numbers before running anything.
 *
 * <p>This checks the denominators rather than the results, because they are what the module can
 * answer without a run: how many subjects the corpus holds, and how they split by contract. The
 * per-run outcomes stay in the generated reports under {@code target/corpus-eval/}, which the
 * documents already name as the authority when the two disagree.
 *
 * <p>The check is deliberately for the number in its sentence, not a regex over every integer in
 * the file. A gate that fails whenever any digit moves gets weakened until it passes; this one
 * fails only when a stated denominator stops being true.
 */
class CorpusClaimsInDocsTest {

    private static final Path README = repoRoot().resolve("README.md");
    private static final Path EVAL = repoRoot().resolve("docs/analysis/corpus-eval.md");

    @Test
    @DisplayName("the subject counts in README and the corpus eval match the corpus")
    void proseAgreesWithTheCorpus() {
        long safe = Corpus.count(Contract.THREAD_SAFE);
        long unsafe = Corpus.count(Contract.NOT_THREAD_SAFE);
        long total = Corpus.subjects().size();

        List<String> stale = new ArrayList<>();
        check(stale, README, total + " subjects",
                "the corpus holds " + total + " subjects");
        check(stale, README, unsafe + " of " + unsafe + " detected",
                "the documented-unsafe group is " + unsafe + " subjects");
        check(stale, README, "0 of " + safe,
                "the documented-safe group is " + safe + " subjects");
        check(stale, EVAL, total + " subjects",
                "the corpus holds " + total + " subjects");
        check(stale, EVAL, safe + " documented-safe",
                "the documented-safe group is " + safe + " subjects");

        assertTrue(stale.isEmpty(),
                "these documents state corpus numbers the corpus no longer produces. The generated "
                        + "reports under target/corpus-eval/ are the authority and the prose is a "
                        + "copy of one run, so the copy is what has to move: " + stale);
    }

    private static void check(List<String> stale, Path document, String claim, String because) {
        if (!read(document).contains(claim)) {
            stale.add(repoRoot().relativize(document) + " no longer says \"" + claim
                    + "\", and " + because);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }

    /** {@return the reactor root, found by walking up from this module's directory} */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("README.md"))
                    && Files.isDirectory(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not find the reactor root above " + Path.of("").toAbsolutePath());
    }
}
