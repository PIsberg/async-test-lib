package se.deversity.asynctest.report;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.deversity.asynctest.diagnostics.GradedFindings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

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
 * com.example.CartServiceTest#addItem_concurrently | RaceConditionDetector | - 'total' written by # threads
 * com.example.CartServiceTest#addItem_concurrently | SharedCollectionDetector
 * </pre>
 *
 * <p>A three-field entry accepts one finding: its third field is the finding's fingerprint (see
 * {@link #fingerprint}), and a later finding of the same detector in the same test that the file
 * does not name still prints and still fails. A two-field entry accepts everything that detector
 * reports in that test. Update mode writes only the first shape since 1.12.3; the second is what
 * earlier releases wrote, and it keeps working so an existing file accepts on upgrade exactly what
 * it accepted before. The runner announces at {@code INFO} when such an entry hides a finding it
 * was not recorded for ({@code baseline.detector-wide.suppressed}).
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

    /**
     * The on-disk format version this release writes, as a {@code # format-version: N} comment
     * line. Readers ignore every {@code #} line, so a file written by any release loads in any
     * other; the marker exists so a future incompatible change can be recognised instead of
     * silently misread. Changing the line shape is an expand-contract change: the reader learns
     * the new shape one release before the writer emits it (see {@code docs/SUPPORT_POLICY.md},
     * "Files at rest").
     */
    public static final int FORMAT_VERSION = 2;

    private static final String FORMAT_VERSION_PREFIX = "# format-version:";

    /**
     * A file written by a release that knows a newer shape is refused loudly rather than
     * misread: silently suppressing the wrong findings, or none, is the failure a baseline
     * exists to prevent. Files with no marker, or an older one, load as before.
     */
    private static void rejectNewerFormat(Path path, String markerLine) {
        String value = markerLine.substring(FORMAT_VERSION_PREFIX.length()).trim();
        int declared;
        try {
            declared = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return; // an unparseable marker is a comment like any other
        }
        if (declared > FORMAT_VERSION) {
            throw new IllegalStateException("Baseline file " + path + " declares format-version "
                    + declared + " but this release reads up to " + FORMAT_VERSION
                    + ". It was written by a newer async-test-lib; upgrade, or regenerate the "
                    + "baseline with -D" + UPDATE_PROPERTY + "=true on this release.");
        }
    }

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
     * @return the baseline named by the system properties, or an empty one when none is configured
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
     * @return {@code true} when findings are being recorded into the baseline rather than gated on
     */
    public static boolean updateMode() {
        return Boolean.getBoolean(UPDATE_PROPERTY);
    }

    /**
     * Loads (with caching by last-modified time) the baseline at {@code path}.
     *
     * @param path the baseline file to read; a missing file yields an empty baseline rather than an error
     * @return the baseline read from that file, or an empty baseline when the file does not exist
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
                if (trimmed.startsWith(FORMAT_VERSION_PREFIX)) {
                    rejectNewerFormat(path, trimmed);
                    continue;
                }
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
     * Returns {@code true} when the file holds a detector-wide entry for (test, detector): the
     * two-field shape earlier releases wrote, which accepts every finding that detector reports
     * in that test. Per-finding entries are answered by {@link #covers}.
     *
     * @param testId the test the finding was raised against
     * @param detectorName the detector that raised the finding
     * @return {@code true} when that detector is baselined wholesale for that test
     */
    public boolean contains(String testId, String detectorName) {
        return entries.contains(key(testId, detectorName));
    }

    /**
     * Returns {@code true} when the file accepts this one finding: a per-finding entry for
     * (test, detector) whose fingerprint equals {@code fingerprint}. A detector-wide entry does
     * not count here; ask {@link #contains} for that.
     *
     * @param testId the test the finding was raised against
     * @param detectorName the detector that raised the finding
     * @param fingerprint the finding's fingerprint, from {@link #fingerprints} or {@link #fingerprint}
     * @return {@code true} when that finding is already baselined
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL)
    public boolean covers(String testId, String detectorName, String fingerprint) {
        return entries.contains(key(testId, detectorName) + " | " + fingerprint);
    }

    /**
     * Number of entries in this baseline.
     *
     * @return the number of baselined findings
     */
    public int size() {
        return entries.size();
    }

    /**
     * Appends detector-wide (test, detector) entries to the baseline file named by
     * {@value #PATH_PROPERTY}, creating it if needed and skipping entries already
     * present. Thread-safe across concurrently-running tests in the same JVM.
     *
     * <p>A detector-wide entry accepts every finding that detector reports in that test,
     * including ones it reports for the first time long after the entry was written. The runner's
     * update mode uses {@link #record(String, Map)} instead, which accepts only the findings seen.
     *
     * @return the number of entries actually added
     *
     * @param testId the test the finding was raised against
     * @param detectorNames the detectors to baseline for this test; entries already present are skipped
     */
    public static int record(String testId, Collection<String> detectorNames) {
        List<String> keys = new ArrayList<>();
        for (String detector : detectorNames) {
            keys.add(key(testId, detector));
        }
        return write(keys);
    }

    /**
     * Appends one per-finding entry for each fingerprint to the baseline file named by
     * {@value #PATH_PROPERTY}, creating it if needed and skipping entries already present.
     * Thread-safe across concurrently-running tests in the same JVM.
     *
     * @param testId the test the findings were raised against
     * @param fingerprintsByDetector each detector's finding fingerprints, from {@link #fingerprints}
     * @return the number of entries actually added
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL)
    public static int record(String testId, Map<String, ? extends Collection<String>> fingerprintsByDetector) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, ? extends Collection<String>> e : fingerprintsByDetector.entrySet()) {
            for (String fingerprint : e.getValue()) {
                keys.add(key(testId, e.getKey()) + " | " + fingerprint);
            }
        }
        return write(keys);
    }

    private static int write(Collection<String> keys) {
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
                merged.addAll(keys);
                int added = merged.size() - before;
                if (added > 0) {
                    Path parent = path.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    List<String> lines = new ArrayList<>();
                    lines.add("# async-test baseline — accepted findings; remove lines as they are fixed");
                    lines.add("# format-version: " + FORMAT_VERSION);
                    lines.add("# format: <testClass>#<testMethod> | <DetectorName> | <finding fingerprint>");
                    lines.add("# a line without the third field accepts every finding of that detector in that test");
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

    /**
     * {@return the fingerprints of the findings in one detector's report, in report order}
     *
     * <p>A report that grades its findings names each one in its grade summary, and those are
     * the findings. Any other report is judged line by line: every non-blank line is a
     * fingerprint, so a finding the baseline has not seen adds at least one line the file does
     * not hold. Lines the detector prints for every finding (its "Why" and "Fix" text) are the
     * same each time and cost only file length.
     *
     * @param report the detector's report text
     * @param grades the report's per-finding grades; empty for an ungraded report
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL)
    public static List<String> fingerprints(String report, List<GradedFindings.Grade> grades) {
        Set<String> out = new LinkedHashSet<>();
        if (grades.isEmpty()) {
            report.lines().map(Baseline::fingerprint).filter(f -> !f.isEmpty()).forEach(out::add);
        } else {
            for (GradedFindings.Grade grade : grades) {
                out.add(fingerprint(grade.summary()));
            }
        }
        return List.copyOf(out);
    }

    /**
     * {@return a finding's text with the parts that change from run to run removed}
     *
     * <p>Colour codes are dropped; identity hash codes, hexadecimal literals and every run of
     * digits (counts, thread numbers, timings) become {@code #}; whitespace collapses to single
     * spaces. What is left names the finding and stays the same between two runs that found the
     * same thing.
     *
     * @param text one finding, or one line of a report
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL)
    public static String fingerprint(String text) {
        String s = ANSI.matcher(text).replaceAll("");
        s = HEX_LITERAL.matcher(s).replaceAll("0x#");
        s = IDENTITY_HASH.matcher(s).replaceAll("@#");
        s = HEX_TOKEN.matcher(s).replaceAll("#");
        s = DIGITS.matcher(s).replaceAll("#");
        return WHITESPACE.matcher(s).replaceAll(" ").trim();
    }

    private static final Pattern ANSI = Pattern.compile((char) 27 + Pattern.quote("[") + "[0-9;]*[A-Za-z]");
    private static final Pattern HEX_LITERAL = Pattern.compile("0[xX][0-9a-fA-F]+");
    private static final Pattern IDENTITY_HASH = Pattern.compile("@[0-9a-fA-F]+");
    private static final Pattern HEX_TOKEN = Pattern.compile(
            "(?<![0-9A-Za-z])(?=[0-9a-fA-F]*[0-9])[0-9a-fA-F]{6,}(?![0-9A-Za-z])");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final Pattern WHITESPACE = Pattern.compile("[ " + (char) 9 + "]+");

    private static String key(String testId, String detectorName) {
        return normalize(testId + " | " + detectorName);
    }

    /**
     * Normalises the spacing around the first two separators. A third field is a fingerprint and
     * is kept as written, separators included: finding text may itself contain {@code |}.
     */
    private static String normalize(String line) {
        int sep = line.indexOf('|');
        if (sep < 0) {
            return line.trim();
        }
        String head = line.substring(0, sep).trim() + " | ";
        String rest = line.substring(sep + 1);
        int second = rest.indexOf('|');
        if (second < 0) {
            return head + rest.trim();
        }
        return head + rest.substring(0, second).trim() + " | " + rest.substring(second + 1).trim();
    }

    private record CachedBaseline(long lastModified, Baseline baseline) {}
}
