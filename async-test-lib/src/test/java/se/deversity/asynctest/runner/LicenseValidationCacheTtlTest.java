package se.deversity.asynctest.runner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a record's age and {@code license.cache.ttl.hours} decide: {@code isFresh} skips online
 * validation, so it is the line between a revoked key failing its next build and a revoked key
 * running on its last success.
 *
 * <p>Every other cache test writes a record and reads it back at once, so none of them could tell
 * {@code isFresh} from "a record exists": the mutant that returns {@code true} for any parsed record
 * survived the whole suite (pitest, mutation run 39105270920). These stage the record's timestamp
 * directly.
 */
class LicenseValidationCacheTtlTest {

    private static final String HASH = LicenseValidationCache.hash("ttl-boundaries");
    private static final long HOUR_MS = 3_600_000L;

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
    void aRecordOlderThanTheTtlIsNotFresh_butStillCountsForTheOutageGrace() throws Exception {
        writeRecord(System.currentTimeMillis() - 25 * HOUR_MS);

        assertFalse(LicenseValidationCache.isFresh(HASH),
                "a 25-hour-old validation under a 24-hour TTL skipped the online check");
        assertTrue(LicenseValidationCache.hasRecord(HASH),
                "age must not cost the grace: hasRecord answers 'ever validated', not 'recently'");
    }

    @Test
    void aRecordWithinTheTtlIsFresh() throws Exception {
        writeRecord(System.currentTimeMillis() - 23 * HOUR_MS);

        assertTrue(LicenseValidationCache.isFresh(HASH));
    }

    @Test
    void aRecordThatIsNotATimestampIsNotFresh() throws Exception {
        Files.writeString(recordFile(), "validated", StandardCharsets.UTF_8);

        assertFalse(LicenseValidationCache.isFresh(HASH));
    }

    @Test
    void ttlZero_alwaysRevalidates_butKeepsTheGrace() throws Exception {
        LicenseValidationCache.record(HASH);
        System.setProperty("license.cache.ttl.hours", "0");

        assertFalse(LicenseValidationCache.isFresh(HASH), "ttl 0 must send every run online");
        assertTrue(LicenseValidationCache.hasRecord(HASH));
    }

    @Test
    void negativeTtl_disablesTheCacheEntirely() throws Exception {
        LicenseValidationCache.record(HASH);
        System.setProperty("license.cache.ttl.hours", "-1");

        assertFalse(LicenseValidationCache.isFresh(HASH));
        assertFalse(LicenseValidationCache.hasRecord(HASH),
                "a negative TTL turns the cache off, so a past success must not admit the outage grace");

        writeRecord(0);
        LicenseValidationCache.record(HASH);
        assertEquals("0", Files.readString(recordFile(), StandardCharsets.UTF_8),
                "a negative TTL must not write records either");
    }

    @Test
    void aTtlThatIsNotANumberIsRefusedNamingTheProperty() {
        System.setProperty("license.cache.ttl.hours", "a day");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> LicenseValidationCache.isFresh(HASH));
        assertTrue(ex.getMessage().contains("license.cache.ttl.hours"), ex.getMessage());
    }

    private Path recordFile() {
        return cacheDir.resolve("validation-" + HASH + ".ok");
    }

    private void writeRecord(long validatedAtMs) throws Exception {
        Files.writeString(recordFile(), Long.toString(validatedAtMs), StandardCharsets.UTF_8);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
