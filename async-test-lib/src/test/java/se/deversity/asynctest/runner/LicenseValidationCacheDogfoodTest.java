package se.deversity.asynctest.runner;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.ConcurrencyTestFor;
import se.deversity.asynctest.OsSensitive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dogfoods {@link LicenseValidationCache}'s concurrent writers with {@code @AsyncTest}.
 *
 * <p>Why this exists: every forked test JVM of a licensed build records its validation in the same
 * {@code ~/.asynctest} file, so concurrent writers to one record are the normal case, not an edge.
 * The class claims two things about them. A reader sees a complete record, never a partial one, and
 * the cache stays best effort: a write that loses the race is dropped. Before this test the second
 * half was untrue on Windows. Replacing a file another thread has open fails there, the failure was
 * swallowed, and the temporary file it was moving stayed behind: about 1,265 of 1,600 writes in
 * this test left one, on every run, in a directory nothing ever cleans.
 *
 * <p>So the body asserts the reader's half on every execution, and the end of the run asserts the
 * directory holds the one record and nothing else. Linux replaces under an open reader, so only the
 * Windows legs could see the leak; the assertions hold on every platform. Tagged
 * {@code @OsSensitive} so that a Windows leg which blocks merging runs it (#907).
 */
@ConcurrencyTestFor(LicenseValidationCache.class)
@OsSensitive
class LicenseValidationCacheDogfoodTest {

    private static final int THREADS = 8;
    private static final int ROUNDS = 200;
    private static final String HASH = LicenseValidationCache.hash("dogfood-concurrent-writers");
    private static final Map<String, String> SAVED = new HashMap<>();

    @TempDir
    static Path cacheDir;

    @BeforeAll
    static void pointTheCacheAtAnEmptyDirectory() {
        set("license.cache.dir", cacheDir.toString());
        set("license.cache.ttl.hours", "24");
    }

    @AsyncTest(threads = THREADS, invocations = ROUNDS, licenseMockMode = true, timeoutMs = 60_000)
    void everyWorkerRecordsTheSameValidationAndReadsItBack() {
        LicenseValidationCache.record(HASH);
        assertTrue(LicenseValidationCache.isFresh(HASH),
                "a record this thread just wrote read back as missing or partial while other "
                        + "threads rewrote it, so a licensed run would revalidate online");
    }

    @AfterAll
    static void theDirectoryHoldsOneRecordAndNoTemporaryFiles() throws IOException {
        try {
            List<String> files;
            try (Stream<Path> listing = Files.list(cacheDir)) {
                files = listing.map(p -> p.getFileName().toString()).sorted().toList();
            }
            assertEquals(List.of("validation-" + HASH + ".ok"), files,
                    files.size() + " files where one record belongs: writes that lost the race "
                            + "left their temporary files in the cache directory, which grows "
                            + "with every concurrent licensed build");
        } finally {
            SAVED.forEach((k, v) -> {
                if (v == null) System.clearProperty(k);
                else System.setProperty(k, v);
            });
        }
    }

    private static void set(String key, String value) {
        if (!SAVED.containsKey(key)) {
            SAVED.put(key, System.getProperty(key));
        }
        System.setProperty(key, value);
    }
}
