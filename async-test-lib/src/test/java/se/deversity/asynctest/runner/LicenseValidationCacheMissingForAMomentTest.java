package se.deversity.asynctest.runner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A record that is absent for a moment, between a replace's two steps, still reads as fresh (#928).
 *
 * <p>The first fix for #928 retried every read failure but a missing file, which it took as the one
 * certain sign of "no record". The concurrent-writers dogfood test then failed again on the Windows
 * leg of main (2026-10-23, run 39991282681) with a record the thread had just written reading back
 * as missing or partial, and nothing in 19,000 local rounds under CPU load reproduced a read
 * failure of any kind. So the one outcome the retry excluded is now covered too: a file that is not
 * there is retried with the same backoff, and only one that stays away for the whole bound is "no
 * record". The cost is paid once per JVM, on a machine with no record yet, before the online
 * validation that would follow anyway.
 *
 * <p>This stages the gap directly: a thread renames the record away and back 30 ms later, and
 * {@code isFresh} is asked while it is gone. Platform-independent, so it runs in every leg.
 */
class LicenseValidationCacheMissingForAMomentTest {

    private static final String HASH = LicenseValidationCache.hash("missing-for-a-moment");

    @TempDir
    Path cacheDir;

    private String savedDir;
    private String savedTtl;

    @BeforeEach
    void pointTheCacheAtAnEmptyDirectory() {
        savedDir = System.getProperty("license.cache.dir");
        savedTtl = System.getProperty("license.cache.ttl.hours");
        System.setProperty("license.cache.dir", cacheDir.toString());
        System.setProperty("license.cache.ttl.hours", "24");
    }

    @AfterEach
    void restore() {
        restore("license.cache.dir", savedDir);
        restore("license.cache.ttl.hours", savedTtl);
    }

    @Test
    void aRecordGoneForAMomentStillReadsAsFresh() throws Exception {
        LicenseValidationCache.record(HASH);
        Path record = cacheDir.resolve("validation-" + HASH + ".ok");
        Path aside = cacheDir.resolve("aside.tmp");
        CountDownLatch gone = new CountDownLatch(1);
        Thread replacer = new Thread(() -> {
            try {
                // Renamed away and back, never rewritten: Files.writeString creates the file empty
                // before it writes, and a retry landing in that instant read "" and answered false
                // in 134 ms, well inside the 255 ms bound (Test Suite (25), 2026-10-27, job
                // 122536020497). record() never leaves a partial file, so neither may the fixture.
                Files.move(record, aside, StandardCopyOption.ATOMIC_MOVE);
                gone.countDown();
                Thread.sleep(30);
                Files.move(aside, record, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        replacer.start();
        gone.await();
        try {
            assertTrue(LicenseValidationCache.isFresh(HASH),
                    "a fresh record that was absent for 30 ms read as no record at all, so a licensed run "
                            + "would validate online again");
            assertTrue(LicenseValidationCache.hasRecord(HASH), "the same gap must not deny the outage grace");
        } finally {
            replacer.join();
        }
    }

    @Test
    void aRecordThatNeverExistedIsStillNoRecord() {
        assertFalse(LicenseValidationCache.isFresh(HASH), "retrying a missing file must not invent a record");
        assertFalse(LicenseValidationCache.hasRecord(HASH));
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
