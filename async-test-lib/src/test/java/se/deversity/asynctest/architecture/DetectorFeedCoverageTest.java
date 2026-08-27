package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.diagnostics.DetectorFeed;
import se.deversity.asynctest.diagnostics.DetectorFeeds;
import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.SharedCollectionDetector;
import se.deversity.asynctest.telemetry.TelemetryBridge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps {@link DetectorFeeds} honest: complete, wired to reality, and mirrored in the catalog.
 *
 * <p>The feed classification answers the question a user asks before attaching the agent: which
 * detectors will ever fire on my code without me recording anything. A wrong answer wastes their
 * setup or their trust, so the three properties a machine can check are checked here:
 *
 * <ul>
 *   <li>every {@code DetectorType} resolves to exactly one feed, and the three sets partition the
 *       enum;
 *   <li>the {@link DetectorFeed#AGENT} set equals the detectors the woven streams are
 *       compile-wired into, read reflectively from the classes that do the wiring, so re-routing
 *       a stream without reclassifying goes red;
 *   <li>the listing in {@code docs/DETECTOR_CATALOG.md} names exactly the classes the table
 *       classifies, per feed and with matching counts, so the documentation cannot drift from
 *       the code the way the trust tiers once did.
 * </ul>
 *
 * <p>The {@link DetectorFeed#ZERO_CONFIG} rows rest on reviewed source evidence, named in
 * {@code DetectorFeeds}' javadoc, the same standing a {@code PROMPT} trust tier has. What a gate
 * could add there, an empty-body run per detector proving a finding can exist, is the accuracy
 * eval's job, not this one's.
 */
class DetectorFeedCoverageTest {

    private static final Path CATALOG = Path.of("docs", "DETECTOR_CATALOG.md");

    @Test
    @DisplayName("every detector resolves to exactly one feed")
    void everyDetectorHasExactlyOneFeed() {
        Set<DetectorType> seen = EnumSet.noneOf(DetectorType.class);
        for (DetectorFeed feed : DetectorFeed.values()) {
            for (DetectorType type : DetectorFeeds.fedBy(feed)) {
                assertTrue(seen.add(type),
                        type + " appears in more than one feed set");
                assertEquals(feed, DetectorFeeds.feedOf(type),
                        type + " answers a different feed than the set that contains it");
            }
        }
        assertEquals(EnumSet.allOf(DetectorType.class), seen,
                "the three feed sets must partition DetectorType exactly");
    }

    @Test
    @DisplayName("the agent-fed set equals the detectors the woven streams are wired into")
    void agentRowsMatchTheWovenWiring() {
        // The field stream: TelemetryBridge is compile-wired to exactly one detector type. Scan
        // every constructor and static factory parameter against the full detector roster, so
        // routing the stream into another detector without reclassifying it goes red here.
        Set<String> bridgeFed = new TreeSet<>();
        for (Constructor<?> constructor : TelemetryBridge.class.getDeclaredConstructors()) {
            collectDetectorParameters(constructor.getParameterTypes(), bridgeFed);
        }
        for (Method method : TelemetryBridge.class.getDeclaredMethods()) {
            collectDetectorParameters(method.getParameterTypes(), bridgeFed);
        }
        assertEquals(Set.of(AtomicityValidator.class.getSimpleName()), bridgeFed,
                "the telemetry bridge's wiring names the detectors the field stream feeds");

        // The collection stream: the hooks reach their detector through this accessor.
        Class<?> collectionFed;
        try {
            Method accessor = AsyncTestContext.class.getDeclaredMethod("currentSharedCollectionDetector");
            collectionFed = accessor.getReturnType();
        } catch (NoSuchMethodException e) {
            throw new AssertionError("AgentCollectionHooks reaches its detector through "
                    + "AsyncTestContext.currentSharedCollectionDetector(); if that accessor moved, "
                    + "point this gate at the new wiring rather than deleting the check", e);
        }
        assertEquals(SharedCollectionDetector.class, collectionFed,
                "the collection stream's accessor names the detector it feeds");

        // The lock stream. AgentLockHooks substitutes every Lock.lock()/unlock()/tryLock() call
        // site, and for a long time handed all of it to HeldLocks alone, so these three saw
        // nothing unless the user wrote record calls by hand. This gate did not notice, because
        // it scanned the bridge and the collection hooks and no third place existed yet: a
        // stream could be routed into a detector with the table still calling it RECORDING.
        // Resolving the accessor proves the wiring's shape; requiring the hook source to name it
        // proves the delivery is still there, which is the half reflection cannot see.
        String lockHooks = read(repoRoot().resolve(Path.of("async-test-lib", "src", "main",
                "java", "se", "deversity", "asynctest", "AgentLockHooks.java")));
        Set<DetectorType> wovenFed = EnumSet.noneOf(DetectorType.class);
        String sharedHooks = read(repoRoot().resolve(Path.of("async-test-lib", "src", "main",
                "java", "se", "deversity", "asynctest", "AgentSharedInstanceHooks.java")));
        String wovenHooks = lockHooks + sharedHooks;
        for (String accessor : List.of("currentLockOrderValidator", "currentLockLeakDetector",
                "currentTryLockMisuseDetector", "currentSimpleDateFormatDetector",
                "currentSharedMatcherDetector", "currentSharedMessageDigestDetector")) {
            Class<?> fed;
            try {
                fed = AsyncTestContext.class.getDeclaredMethod(accessor).getReturnType();
            } catch (NoSuchMethodException e) {
                throw new AssertionError("AgentLockHooks reaches its detectors through "
                        + "AsyncTestContext." + accessor + "(); if that accessor moved, point "
                        + "this gate at the new wiring rather than deleting the check", e);
            }
            assertTrue(wovenHooks.contains(accessor + "()"),
                    "AsyncTestContext." + accessor + "() exists but AgentLockHooks no longer "
                            + "calls it, so that woven stream no longer reaches a detector and "
                            + fed.getSimpleName() + " is only reachable by hand. Either restore "
                            + "the delivery or move the detector back to RECORDING in "
                            + "DetectorFeeds, so the table keeps matching what the agent does.");
            wovenFed.add(typeOf(fed.getSimpleName()));
        }

        Set<DetectorType> expected = EnumSet.noneOf(DetectorType.class);
        expected.add(typeOf(AtomicityValidator.class.getSimpleName()));
        expected.add(typeOf(SharedCollectionDetector.class.getSimpleName()));
        expected.addAll(wovenFed);
        assertEquals(expected, DetectorFeeds.fedBy(DetectorFeed.AGENT),
                "AGENT rows must be exactly the detectors the woven streams reach");
    }

    @Test
    @DisplayName("the catalog's feed listing names exactly what the table classifies")
    void catalogListingMatchesTheTable() {
        String catalog = read(repoRoot().resolve(CATALOG));
        assertHeadingMatches(catalog, "Agent-fed", DetectorFeed.AGENT);
        assertHeadingMatches(catalog, "Zero-config", DetectorFeed.ZERO_CONFIG);
        assertHeadingMatches(catalog, "Recording-only", DetectorFeed.RECORDING);
    }

    private static void assertHeadingMatches(String catalog, String heading, DetectorFeed feed) {
        Matcher section = Pattern
                .compile("### " + heading + " \\((\\d+)\\)\n(.*?)(?=\n#|\n---)", Pattern.DOTALL)
                .matcher(catalog.replace("\r\n", "\n"));
        assertTrue(section.find(), "docs/DETECTOR_CATALOG.md must carry a '### " + heading
                + " (N)' listing under 'What feeds each detector'");

        // Only the last paragraph of the section is the listing; the prose above it names the
        // JVM types a feed reads, and those are not detectors.
        String[] paragraphs = section.group(2).strip().split("\n\\s*\n");
        String listing = paragraphs[paragraphs.length - 1];

        Set<String> listed = new LinkedHashSet<>();
        Matcher names = Pattern.compile("`([A-Za-z0-9]+)`").matcher(listing);
        while (names.find()) {
            listed.add(names.group(1));
        }

        Set<String> classified = new LinkedHashSet<>();
        for (DetectorType type : DetectorFeeds.fedBy(feed)) {
            classified.add(rowOf(type));
        }

        assertEquals(classified, listed,
                "the catalog's " + heading + " listing and DetectorFeeds must name the same "
                        + "detector classes");
        assertEquals(classified.size(), Integer.parseInt(section.group(1)),
                "the count in the '" + heading + "' heading must match the listing");
    }

    private static void collectDetectorParameters(Class<?>[] parameters, Set<String> into) {
        for (Class<?> parameter : parameters) {
            for (DetectorTrust.Row row : DetectorTrust.rows()) {
                if (parameter.getSimpleName().equals(row.detectorClass())) {
                    into.add(parameter.getSimpleName());
                }
            }
        }
    }

    private static DetectorType typeOf(String detectorClass) {
        for (DetectorTrust.Row row : DetectorTrust.rows()) {
            if (row.detectorClass().equals(detectorClass)) {
                return row.type();
            }
        }
        throw new AssertionError(detectorClass + " has no DetectorTrust row");
    }

    private static String rowOf(DetectorType type) {
        for (DetectorTrust.Row row : DetectorTrust.rows()) {
            if (row.type() == type) {
                return row.detectorClass();
            }
        }
        throw new AssertionError(type + " has no DetectorTrust row");
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not find the reactor root above " + Path.of("").toAbsolutePath());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
