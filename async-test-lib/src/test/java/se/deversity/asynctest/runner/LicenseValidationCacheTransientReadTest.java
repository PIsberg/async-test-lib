package se.deversity.asynctest.runner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.asynctest.OsSensitive;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A record another thread or process holds for a moment still reads as fresh (#928).
 *
 * <p>Every forked JVM of a licensed build writes its validation to the same file, and on Windows a
 * read that meets a replace in progress fails with {@code AccessDeniedException}. Measured: the
 * concurrent-writers dogfood test, run 8 times under 8 CPU-bound threads, had one read fail that
 * way on a record that existed, and {@code isFresh} treated the failure as "no fresh record", which
 * sends a licensed run back to validate online. Only a missing file means no record; any other
 * read failure is retried for a moment first.
 *
 * <p>A replace in progress cannot be staged on demand, so this test makes the file unreadable the
 * other way Windows offers, an exclusive lock held for 50 ms, which fails a read the same way:
 * with an {@code IOException} that is not {@code NoSuchFileException}. Linux locks are advisory and
 * never fail the read, so the test runs where the defect lives.
 */
@OsSensitive
@EnabledOnOs(OS.WINDOWS)
class LicenseValidationCacheTransientReadTest {

    private static final String HASH = LicenseValidationCache.hash("transient-read");

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
    void aRecordLockedForAMomentStillReadsAsFresh() throws Exception {
        LicenseValidationCache.record(HASH);
        Path record = cacheDir.resolve("validation-" + HASH + ".ok");
        CountDownLatch locked = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (FileChannel channel = FileChannel.open(record, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                locked.countDown();
                Thread.sleep(50);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        holder.start();
        locked.await();
        try {
            assertTrue(LicenseValidationCache.isFresh(HASH),
                    "a fresh record that was unreadable for 50 ms read as no record at all, so a "
                            + "licensed run would validate online again");
        } finally {
            holder.join();
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
