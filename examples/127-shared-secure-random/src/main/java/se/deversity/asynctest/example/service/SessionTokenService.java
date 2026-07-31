package se.deversity.asynctest.example.service;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Mints session tokens for authenticated users.
 *
 * <p>{@link SecureRandom} is the awkward case in this family. The javadoc does not promise
 * thread safety; it says implementations "should" be safe, leaving it to the provider:
 *
 * <blockquote>A {@code SecureRandom} object is thread-safe if the underlying implementation
 * is thread-safe. [...] Applications are encouraged to use a separate {@code SecureRandom}
 * instance per thread.</blockquote>
 *
 * <p>The bundled SUN providers do synchronize, so on a stock JVM a shared instance is
 * correct — and slow, because every thread serialises on the same lock at exactly the moment
 * every request needs a token. Swap in an HSM-backed, PKCS#11 or FIPS provider and the
 * guarantee is theirs to make, not the JDK's. A provider that does not synchronize can hand
 * two concurrent requests the same bytes, which in this class means two users sharing a
 * session.
 *
 * <p>The advice is the same either way: an instance per thread. It is faster where sharing
 * was safe and correct where it was not, so there is nothing to weigh up.
 */
public final class SessionTokenService {

    private static final int TOKEN_BYTES = 32;

    /** BUG: one instance behind every token this process ever issues. */
    private final SecureRandom sharedRandom = new SecureRandom();

    /** The fix: one instance per thread, seeded independently by the provider. */
    private static final ThreadLocal<SecureRandom> PER_THREAD =
            ThreadLocal.withInitial(SecureRandom::new);

    /** Mints a token from the shared instance — a lock at best, a duplicate at worst. */
    public String mintToken() {
        byte[] token = new byte[TOKEN_BYTES];
        sharedRandom.nextBytes(token);
        return HexFormat.of().formatHex(token);
    }

    /** Mints a token from this thread's own instance. */
    public String mintTokenSafely() {
        byte[] token = new byte[TOKEN_BYTES];
        PER_THREAD.get().nextBytes(token);
        return HexFormat.of().formatHex(token);
    }

    /**
     * A strong instance, per thread. {@code getInstanceStrong()} reads the algorithm from
     * {@code securerandom.strongAlgorithms} and can block on entropy — which is another
     * reason not to put one behind a shared lock.
     */
    public String mintStrongToken() throws NoSuchAlgorithmException {
        byte[] token = new byte[TOKEN_BYTES];
        SecureRandom.getInstanceStrong().nextBytes(token);
        return HexFormat.of().formatHex(token);
    }

    public SecureRandom sharedRandom() {
        return sharedRandom;
    }
}
