package se.deversity.asynctest.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Refuses a map or set in the library keyed by an identity hash, following the hash through the
 * compiled classes (#803).
 *
 * <p>The library's {@code DetectorStateIsKeyedByIdentityTest} reads the source and follows the
 * hash one statement, one local or one single-{@code return} helper; a hash stored in a field,
 * passed through two helpers, or taken from {@code Object.hashCode()} of a class that does not
 * override it went past it. This test runs {@link IdentityHashKeyScanner} over every class of the
 * library as one set, so fields and helpers are followed across classes. It lives here, not in the
 * library, because ASM may not leave this module (invariant 5); this module's tests reach the
 * library's classes through a test-scope dependency and read them as bytes, loading none. The
 * agent's classes are scanned the same way, through a second test-scope dependency (#895): its
 * hooks live in the library, but state the agent keeps itself would otherwise pass unread.
 *
 * <p>The source gate stays: it also counts a hash anywhere inside a key expression, including an
 * argument to a JDK method this scanner does not pass a hash through.
 */
class LibraryStateIsKeyedByIdentityTest {

    /** A class of the library that every build has, used to find where its classes are. */
    private static final String ANCHOR = "se/deversity/asynctest/AsyncTest.class";

    /** Well under the library's class count, so a scan that found the wrong place cannot pass. */
    private static final int MIN_CLASSES = 500;

    /** A class of the agent that every build has. */
    private static final String AGENT_ANCHOR = "se/deversity/asynctest/agent/AsyncTestAgent.class";

    /** Under the agent's class count (33 on 2026-10-03), for the same reason as MIN_CLASSES. */
    private static final int MIN_AGENT_CLASSES = 20;

    /** Where the agent's jar relocates Byte Buddy, which is not this project's code. */
    private static final String SHADED = "/shaded/";

    /** The lock table the agent's spinlock hooks share, keyed by hash on purpose. */
    private static final String SPIN_LOCKS = "keys by hash plus a weak reference, so nothing is "
            + "retained, and checks Lock.isFor on every lookup: a colliding second object goes "
            + "undeclared, which can only report an access, never hide one";

    /**
     * AtomicityValidator's ownership and publication state, keyed by the receiver's identity hash
     * as its {@code instances} javadoc says, reached through the ring's event fields (#803).
     */
    private static final String OWNERSHIP = "AtomicityValidator keys its ownership, publication "
            + "and settle state by identity hash on purpose, as the javadoc on its instances map "
            + "says: a collision merges two objects' states, which can only publish one sooner or "
            + "withhold an excuse, so it costs a finding on correct code and never hides one; the "
            + "per-instance grouping, where a merge would invent a finding, is keyed by the object "
            + "and falls back to the hash only for a caller that passed no receiver";

    /** The drain loop hands every event field to the bridge, so it carries each key above. */
    private static final String RING = "hands the ring's event fields to the bridge and the "
            + "validator, whose identity keys are the ownership state above; the attribution set "
            + "it also reaches is keyed by a thread id carried in the same long slot as a lock "
            + "fingerprint, which a per-field taint cannot tell apart";

    /**
     * Methods that key by an identity hash on purpose or where the merge is only in printed text,
     * as {@code SourceFile#method}, each with a reason a reviewer can check against the method. Per
     * method rather than per file, so a state map keyed by a hash elsewhere in the same file still
     * fails.
     */
    private static final Map<String, String> DELIBERATE = Map.ofEntries(
            Map.entry("AtomicityValidator.java#analyzeAtomicity", OWNERSHIP),
            Map.entry("AtomicityValidator.java#everyPublishedValueWentQuiet", OWNERSHIP),
            Map.entry("AtomicityValidator.java#withdrawExclusivityFromContestedGenerations", OWNERSHIP),
            Map.entry("TelemetryBridge.java#record", OWNERSHIP),
            Map.entry("TelemetryEventBuffer.java#drain", RING),
            Map.entry("SpinLocks.java#lockFor", SPIN_LOCKS),
            Map.entry("SpinLocks.java#aboutToAcquire", SPIN_LOCKS),
            Map.entry("SpinLocks.java#release", SPIN_LOCKS),
            Map.entry("SpinLocks.java#declare", SPIN_LOCKS),
            Map.entry("LambdaLostUpdateDetector.java#collide", "groups read-modify-writes by the "
                    + "rendered value on purpose, so values that print alike are one group; the "
                    + "identity hash is only the rendering of a value whose toString() throws"),
            Map.entry("LambdaLostUpdateDetector.java#unaccountedReads", "counts reads and writes per "
                    + "rendered value, the same grouping as collide"));

    @Test
    @DisplayName("no agent state is keyed by an identity hash, however the hash gets there")
    void noIdentityHashKeysInTheAgent() {
        List<byte[]> classes = classesNextTo(AGENT_ANCHOR);
        assertTrue(classes.size() >= MIN_AGENT_CLASSES, "found only " + classes.size() + " classes next "
                + "to " + AGENT_ANCHOR + "; the scan is looking in the wrong place and would pass by "
                + "scanning nothing");

        List<String> offenders = new ArrayList<>();
        for (IdentityHashKeyScanner.Finding f : IdentityHashKeyScanner.scan(classes,
                LibraryStateIsKeyedByIdentityTest.class.getClassLoader())) {
            offenders.add(f.toString());
        }

        assertTrue(offenders.isEmpty(),
                "these agent calls key a map or set by an identity hash, which merges two objects "
                        + "whose hashes collide. Key by the object in an identity map, or by a value "
                        + "that is already unique: " + offenders);
    }

    @Test
    @DisplayName("no library state is keyed by an identity hash, however the hash gets there")
    void noIdentityHashKeys() {
        List<byte[]> classes = classesNextTo(ANCHOR);
        assertTrue(classes.size() >= MIN_CLASSES, "found only " + classes.size() + " classes next to "
                + ANCHOR + "; the scan is looking in the wrong place and would pass by scanning nothing");

        Map<String, List<String>> offenders = new TreeMap<>();
        for (IdentityHashKeyScanner.Finding f : IdentityHashKeyScanner.scan(classes,
                LibraryStateIsKeyedByIdentityTest.class.getClassLoader())) {
            offenders.computeIfAbsent(f.sourceFile() + "#" + f.method(), k -> new ArrayList<>()).add(f.toString());
        }
        Set<String> stale = new TreeSet<>(DELIBERATE.keySet());
        stale.removeAll(offenders.keySet());
        DELIBERATE.keySet().forEach(offenders::remove);

        assertTrue(offenders.isEmpty(),
                "these calls key a map or set by an identity hash, which merges two objects whose "
                        + "hashes collide and silently attributes one's events to the other. Key by "
                        + "IdentityKey instead (it caches the hash and compares referents with ==), or "
                        + "by a value that is already unique, such as a thread id: " + offenders);
        assertTrue(stale.isEmpty(),
                "a method excused as deliberate no longer matches, so its exemption would only hide "
                        + "the next key written there; remove it from DELIBERATE: " + stale);
    }

    /**
     * {@return the bytes of every class in {@code anchorClass}'s package and the packages below it,
     * read from its directory or its jar}
     *
     * <p>Only that package tree, and not its {@code shaded} package: under Gradle the agent arrives as
     * its shaded jar, with Byte Buddy relocated under {@code agent/shaded/}, and Byte Buddy's own
     * maps are not this project's to judge (#895). Maven's reactor hands over the unshaded classes.
     *
     * @param anchorClass the resource path of one class the module always has, in its top package
     */
    private static List<byte[]> classesNextTo(String anchorClass) {
        URL anchor = LibraryStateIsKeyedByIdentityTest.class.getClassLoader().getResource(anchorClass);
        if (anchor == null) {
            throw new IllegalStateException(anchorClass + " is not on the test classpath; this module's "
                    + "pom declares async-test-lib and async-test-agent as test dependencies for this test");
        }
        String packagePath = anchorClass.substring(0, anchorClass.lastIndexOf('/') + 1);
        List<byte[]> classes = new ArrayList<>();
        try {
            if ("jar".equals(anchor.getProtocol())) {
                JarURLConnection connection = (JarURLConnection) anchor.openConnection();
                connection.setUseCaches(false);
                try (JarFile jar = new JarFile(Path.of(connection.getJarFileURL().toURI()).toFile())) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        if (entry.getName().startsWith(packagePath) && !entry.getName().contains(SHADED)
                                && entry.getName().endsWith(".class")) {
                            try (InputStream in = jar.getInputStream(entry)) {
                                classes.add(in.readAllBytes());
                            }
                        }
                    }
                }
            } else {
                Path root = Path.of(anchor.toURI()).getParent();
                try (Stream<Path> files = Files.walk(root)) {
                    for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class")
                            && !p.endsWith("module-info.class"))::iterator) {
                        classes.add(Files.readAllBytes(file));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the classes from " + anchor, e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("could not locate the classes from " + anchor, e);
        }
        return classes;
    }
}
