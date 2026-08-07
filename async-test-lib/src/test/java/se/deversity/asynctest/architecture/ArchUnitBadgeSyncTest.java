package se.deversity.asynctest.architecture;

import com.tngtech.archunit.junit.ArchTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.AnnotatedElement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the rule count in the README's ArchUnit badge to the rules {@link ArchitectureTest}
 * actually declares.
 *
 * <p><strong>The failure this prevents.</strong> The badge reads
 * {@code ArchUnit-20_rules_passing}, and 20 is a number written in prose: nothing recomputes it.
 * Adding or deleting an {@code @ArchTest} leaves the badge asserting a count that was true at
 * some earlier commit, and a badge that is quietly wrong is worse than no badge, because a reader
 * uses it to decide how much architectural enforcement this project has. The same reasoning is
 * why {@code BuildMetadataSyncTest} fails when a version literal drifts out of {@code pom.xml}.
 *
 * <p>The count is taken by reflection over the compiled class rather than by grepping the source
 * for {@code @ArchTest}, so it measures the rule set the ArchUnit engine will run: a rule that is
 * commented out, renamed or moved to a method changes this number the same way the engine sees it.
 */
class ArchUnitBadgeSyncTest {

    /** Matches the count inside {@code ArchUnit-<N>_rules_passing} in the shields.io badge URL. */
    private static final Pattern BADGE_COUNT =
            Pattern.compile("ArchUnit-(\\d+)_rules_passing");

    @Test
    @DisplayName("the README ArchUnit badge states the number of rules that actually exist")
    void badgeCountMatchesDeclaredRules() {
        long declaredRules = declaredArchRules();
        assertTrue(declaredRules > 0,
                "Found no @ArchTest members on ArchitectureTest. Either the rules moved to another "
                        + "class, in which case this test and the README badge have to follow them, "
                        + "or @ArchTest lost its runtime retention and this count is meaningless.");

        String readme = read(repoRoot().resolve("README.md"));
        Matcher matcher = BADGE_COUNT.matcher(readme);
        assertTrue(matcher.find(),
                "README.md has no ArchUnit badge matching " + BADGE_COUNT.pattern() + ". The badge "
                        + "is how a reader sees that this project enforces its module boundaries; "
                        + "if it was removed on purpose, remove this test with it.");

        int badgeCount = Integer.parseInt(matcher.group(1));
        assertEquals(declaredRules, badgeCount,
                "The README ArchUnit badge says " + badgeCount + " rules but ArchitectureTest "
                        + "declares " + declaredRules + ". Update the badge in README.md to "
                        + "ArchUnit-" + declaredRules + "_rules_passing.");
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    /**
     * Counts the {@code @ArchTest} members ArchUnit will run. Fields hold the rules today; methods
     * are counted too because {@code @ArchTest} is valid on both and a rule that migrates from one
     * form to the other must not change the total.
     */
    private static long declaredArchRules() {
        return Stream.concat(
                        Stream.of(ArchitectureTest.class.getDeclaredFields()),
                        Stream.of(ArchitectureTest.class.getDeclaredMethods()))
                .map(AnnotatedElement.class::cast)
                .filter(member -> member.isAnnotationPresent(ArchTest.class))
                .count();
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))
                    && Files.isRegularFile(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not find the reactor root above " + Path.of("").toAbsolutePath());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
