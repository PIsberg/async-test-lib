package se.deversity.asynctest.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Known-findings baseline for adopting async-test on a legacy codebase.
 *
 * <p>A baseline file lists detector findings that are accepted for now; matching
 * findings are suppressed from the {@code @AsyncTest(failOn = ...)} gate instead
 * of failing the build, letting teams enable fail-gating immediately and ratchet
 * the baseline down over time.
 *
 * <p><b>File format</b> — one entry per line, diff-friendly and hand-editable:
 * <pre>
 * # comments and blank lines are ignored
 * com.example.CartServiceTest#addItem_concurrently | RaceConditionDetector
 * com.example.CartServiceTest#addItem_concurrently | SharedCollectionDetector
 * </pre>
 *
 * <p><b>System properties</b>:
 * <ul>
 *   <li>{@code -Dasync-test.baseline=<path>} — baseline file to apply (no default).</li>
 *   <li>{@code -Dasync-test.baseline.update=true} — instead of failing, append the
 *       findings that would have failed to the baseline file.</li>
 * </ul>
 *
 * @since 1.7.0
 */
public final class Baseline {

    private static final Logger log = LoggerFactory.getLogger(Baseline.class);

    /** System property naming the baseline file to apply. */
    public static final String PATH_PROPERTY = "async-test.baseline";

    /** System property enabling update (record) mode. */
    public static final String UPDATE_PROPERTY = "async-test.baseline.update";

    private static final Baseline EMPTY = new Baseline(Set.of());

    /** Cache keyed by path; entries invalidated by last-modified time. */
    private static final ConcurrentMap<Path, CachedBaseline> CACHE = new ConcurrentHashMap<>();

    /** Guards read-merge-write cycles in {@link #record}. */
    private static final Object WRITE_LOCK = new Object();

    private final Set<String> entries;

    private Baseline(Set<String> entries) {
        this.entries = entries;
    }

    /**
     * Resolves the active baseline from {@value #PATH_PROPERTY}; returns an empty
     * baseline when the property is unset. A missing file is treated as empty in
     * update mode (it will be created) and logged once otherwise.
     *
     * @return the from system properties
     */
    public static Baseline fromSystemProperties() {
        String prop = System.getProperty(PATH_PROPERTY);
        if (prop == null || prop.isBlank()) {
            return EMPTY;
        }
        return load(Path.of(prop));
    }

    /**
     * Whether {@value #UPDATE_PROPERTY} is set, switching the gate to record mode.
     *
     * @return the update mode
     */
    public static boolean updateMode() {
        return Boolean.getBoolean(UPDATE_PROPERTY);
    }

    /**
     * Loads (with caching by last-modified time) the baseline at {@code path}.
     *
     * @param path the path
     * @return the load
     */
    public static Baseline load(Path path) {
        if (!Files.exists(path)) {
            if (!updateMode()) {
                log.warn("[AsyncTest] Baseline file not found: {} — no findings will be suppressed", path);
            }
            return new Baseline(Set.of());
        }
        try {
            long lastModified = Files.getLastModifiedTime(path).toMillis();
            CachedBaseline cached = CACHE.get(path);
            if (cached != null && cached.lastModified == lastModified) {
                return cached.baseline;
            }
            Set<String> entries = new TreeSet<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                entries.add(normalize(trimmed));
            }
            Baseline loaded = new Baseline(Set.copyOf(entries));
            CACHE.put(path, new CachedBaseline(lastModified, loaded));
            return loaded;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read baseline file: " + path, e);
        }
    }

    /**
     * Returns {@code true} when the (test, detector) finding is baselined.
     *
     * @param testId the test id
     * @param detectorName the detector name
     * @return the contains
     */
    public boolean contains(String testId, String detectorName) {
        return entries.contains(key(testId, detectorName));
    }

    /**
     * Number of entries in this baseline.
     *
     * @return the size
     */
    public int size() {
        return entries.size();
    }

    /**
     * Appends the given (test, detector) findings to the baseline file named by
     * {@value #PATH_PROPERTY}, creating it if needed and skipping entries already
     * present. Thread-safe across concurrently-running tests in the same JVM.
     *
     * @return the number of entries actually added
     *
     * @param testId the test id
     * @param detectorNames the detector names
     */
    public static int record(String testId, Collection<String> detectorNames) {
        String prop = System.getProperty(PATH_PROPERTY);
        if (prop == null || prop.isBlank()) {
            log.warn("[AsyncTest] {} set but {} is not — nothing recorded", UPDATE_PROPERTY, PATH_PROPERTY);
            return 0;
        }
        Path path = Path.of(prop);
        synchronized (WRITE_LOCK) {
            try {
                Set<String> merged = new TreeSet<>();
                if (Files.exists(path)) {
                    merged.addAll(load(path).entries);
                }
                int before = merged.size();
                for (String detector : detectorNames) {
                    merged.add(key(testId, detector));
                }
                int added = merged.size() - before;
                if (added > 0) {
                    Path parent = path.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    List<String> lines = new java.util.ArrayList<>();
                    lines.add("# async-test baseline — accepted findings; remove lines as they are fixed");
                    lines.add("# format: <testClass>#<testMethod> | <DetectorName>");
                    lines.addAll(merged);
                    Files.write(path, lines, StandardCharsets.UTF_8);
                    CACHE.remove(path); // next load() re-reads the merged file
                }
                return added;
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to update baseline file: " + path, e);
            }
        }
    }

    private static String key(String testId, String detectorName) {
        return normalize(testId + " | " + detectorName);
    }

    private static String normalize(String line) {
        int sep = line.indexOf('|');
        if (sep < 0) {
            return line.trim();
        }
        return line.substring(0, sep).trim() + " | " + line.substring(sep + 1).trim();
    }

    private record CachedBaseline(long lastModified, Baseline baseline) {}
}
