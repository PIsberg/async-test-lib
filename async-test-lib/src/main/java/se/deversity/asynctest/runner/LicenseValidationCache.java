package se.deversity.asynctest.runner;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Best-effort, cross-JVM record of successful online license validations.
 *
 * <p>Two consumers, two guarantees. {@link #isFresh} lets a new JVM skip revalidation inside the
 * TTL: with {@code forkEvery = 1} every test class is its own JVM, so without this a licensed
 * 500-class suite makes 500 licensing API calls per build. {@link #hasRecord} feeds the outage
 * grace decision in {@link LicenseGuard}: "this exact configuration has validated successfully
 * before" is what separates a provider outage from fabricated credentials that were never valid.
 *
 * <p>Only a hash of the configuration fingerprint ever touches disk — never the key, the email or
 * any other component. The file content is the validation epoch in milliseconds.
 *
 * <p>Every filesystem failure is swallowed: a cache that cannot be read or written must degrade to
 * "validate online every time", never to an error, because this class runs inside somebody else's
 * test suite. Tampering is not defended against; a forged cache file skips revalidation for at
 * most the TTL, which is strictly weaker than the documented {@code -Dlicense.mock.mode=true}
 * bypass one property away.
 *
 * <p>Properties: {@code license.cache.dir} (default {@code ~/.asynctest}) and
 * {@code license.cache.ttl.hours} (default 24; {@code 0} records successes but never skips
 * revalidation; negative disables the cache entirely, including the grace record).
 */
@AIThreadSafe(
    strategy = AIThreadSafe.Strategy.OTHER,
    note = "Stateless static methods over the filesystem. Concurrent writers race on an atomic "
        + "temp-file move where the losing write is equivalent to the winning one; readers see "
        + "either the old complete file or the new complete file, never a partial write. On "
        + "Windows a read that meets a replace in progress fails with AccessDeniedException, or "
        + "finds no file for a moment, so isFresh and hasRecord retry any read failure, a missing "
        + "file included, for up to 255 ms before answering no (#928)."
)
@AISecure(aspect = "authorization (isFresh skips online validation; hasRecord admits outage grace)")
final class LicenseValidationCache {

    private static final long DEFAULT_TTL_HOURS = 24;

    /**
     * Reads of a record before {@link #isFresh} and {@link #hasRecord} give up: backoff 1, 2, 4 ...
     * 128 ms, 255 ms in all, paid only while the file cannot be read or is not there.
     */
    private static final int READ_ATTEMPTS = 9;

    private LicenseValidationCache() {}

    /** SHA-256 hex of the fingerprint material; the only form of it that ever reaches disk. */
    static String hash(String fingerprintMaterial) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(fingerprintMaterial.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JCA algorithm", e);
        }
    }

    /** Whether a successful validation for this hash is within the TTL, so revalidation can be skipped. */
    static boolean isFresh(String hash) {
        long ttlHours = ttlHours();
        if (ttlHours <= 0) {
            return false;
        }
        try {
            String content = readRetrying(fileFor(hash));
            if (content == null) {
                return false;
            }
            long validatedAt = Long.parseLong(content.trim());
            return System.currentTimeMillis() - validatedAt < ttlHours * 3_600_000L;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * {@return the record's content, or {@code null} when there is no record}
     *
     * <p>Every forked JVM of a licensed build writes the same file, and on Windows a read that meets
     * a replace in progress fails with {@code AccessDeniedException}: measured once in eight runs of
     * the concurrent-writers dogfood test under CPU load (#928). Read as "no record", that sends a
     * licensed run back online, so every failure is retried with backoff before it counts. A
     * missing file is retried the same way, not taken as the answer: the first fix took it as the
     * one certain sign of no record, and the dogfood test then failed again on main's Windows leg
     * (2026-10-23) with a record the thread had just written reading back as missing or partial,
     * which nothing reproduced locally. A file that stays away for the whole bound is no record,
     * which costs a machine with no record yet one wait before the online validation it would do
     * anyway. The retry changes nothing about what is accepted: the content must still parse to a
     * timestamp within the TTL.
     */
    private static @Nullable String readRetrying(Path file) throws IOException {
        long backoffMs = 1;
        for (int attempt = 1; ; attempt++) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                if (attempt >= READ_ATTEMPTS) {
                    return null;
                }
                pause(backoffMs, e);
            } catch (IOException e) {
                if (attempt >= READ_ATTEMPTS) {
                    throw e;
                }
                pause(backoffMs, e);
            }
            backoffMs *= 2;
        }
    }

    /** Sleeps {@code ms} between read attempts; an interrupt ends the retry by rethrowing {@code cause}. */
    private static void pause(long ms, IOException cause) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cause.addSuppressed(interrupted);
            throw cause;
        }
    }

    /**
     * Whether this hash has ever validated successfully, regardless of age. Read with the same
     * retry as {@link #isFresh}: a record absent for a moment must not deny the outage grace (#928).
     */
    static boolean hasRecord(String hash) {
        if (ttlHours() < 0) {
            return false;
        }
        try {
            return readRetrying(fileFor(hash)) != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Records a successful online validation for this hash; silently a no-op on any failure. */
    static void record(String hash) {
        if (ttlHours() < 0) {
            return;
        }
        try {
            Path file = fileFor(hash);
            Path parent = file.getParent();
            if (parent == null) {
                return;   // fileFor always resolves inside a directory, and the temp file must share it
            }
            Files.createDirectories(parent);
            // A unique name, not one derived from the thread id: the main thread of every forked
            // test JVM has the same id, so parallel forks recording at once shared one temp file
            // and could move each other's half-written content into place.
            Path tmp = Files.createTempFile(parent, file.getFileName() + ".", ".tmp");
            try {
                Files.writeString(tmp, Long.toString(System.currentTimeMillis()), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                // Windows refuses to replace a record another thread or JVM has open, and that
                // failure is dropped below by design. The temp file it was moving must not be:
                // left behind, one accumulated per lost race in a directory nothing cleans.
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | RuntimeException ignored) {
            // Best effort by design: the next JVM validates online instead.
        }
    }

    private static Path fileFor(String hash) {
        String dir = System.getProperty("license.cache.dir");
        Path base = dir != null && !dir.isBlank()
            ? Path.of(dir)
            : Path.of(System.getProperty("user.home", "."), ".asynctest");
        return base.resolve("validation-" + hash + ".ok");
    }

    private static long ttlHours() {
        String raw = System.getProperty("license.cache.ttl.hours");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TTL_HOURS;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "license.cache.ttl.hours must be a number, got '" + raw + "'", e);
        }
    }
}
