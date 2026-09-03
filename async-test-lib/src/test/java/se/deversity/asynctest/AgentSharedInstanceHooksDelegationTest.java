package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A shared-instance hook must do what the call it replaced did, and hand back what that call
 * returned.
 *
 * <p>The sibling tests ask whether a hook records. That is half of the contract: the agent
 * substitutes the hook for the original invocation, so a hook that records and then forgets to
 * delegate silently deletes the caller's {@code digest.update(...)} or {@code calendar.set(...)},
 * and a hook that returns a fabricated value corrupts the result the call site reads. Neither
 * fault is visible to a test that only asks the detector what it saw, which is why PIT reported
 * thirteen survivors here after #474 (#427): four {@code update} and two {@code set} with the
 * delegated JDK call removed, and return-value mutants on {@code find}, {@code matches},
 * {@code parse}, {@code format} and {@code digest}.
 *
 * <p>Each case below compares the hook against a twin receiver driven through the original JDK
 * call, or pins the one return value the mutation would change. No context is installed: the
 * detector lookup returns null, the recording branch is skipped, and what remains under test is
 * the delegation alone.
 */
class AgentSharedInstanceHooksDelegationTest {

    private static final byte[] PAYLOAD = "corpus-42".getBytes(StandardCharsets.UTF_8);

    /** One day after the epoch, the instant every date case below uses. */
    private static final long DAY_ONE = 86_400_000L;

    /** Matches {@code corpus-42} whole, and is found inside a string with text around it. */
    private static final Pattern PATTERN = Pattern.compile("(?<word>[a-z]+)-(?<num>[0-9]+)");

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A formatter whose output does not depend on the machine's locale or zone. */
    private static SimpleDateFormat utcFormat() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format;
    }

    /** A calendar at a fixed instant, so two of them start out equal. */
    private static Calendar utcCalendar() {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        calendar.setTimeInMillis(0L);
        return calendar;
    }

    /**
     * Drives one digest through {@code hooked} and a second through {@code direct}, and requires
     * the two to have accumulated the same bytes.
     *
     * <p>{@code digest()} is the only way to read a {@code MessageDigest}'s state, so the
     * comparison is of the final hashes. A hook whose {@code update} delegation is removed hashes
     * nothing and produces the empty-input digest instead.
     */
    private static void assertSameAccumulation(String what, Consumer<MessageDigest> hooked,
                                               Consumer<MessageDigest> direct) {
        MessageDigest viaHook = sha256();
        MessageDigest viaJdk = sha256();
        hooked.accept(viaHook);
        direct.accept(viaJdk);
        byte[] hookHash = viaHook.digest();
        byte[] jdkHash = viaJdk.digest();
        assertFalse(Arrays.equals(sha256().digest(), jdkHash),
                what + ": the fixture must actually feed the digest, or this comparison is two "
                        + "empty digests agreeing");
        assertArrayEquals(jdkHash, hookHash,
                what + " must accumulate exactly what the call it replaced accumulates; the hook "
                        + "is substituted for that call, so a dropped delegation deletes the "
                        + "caller's data");
    }

    @Test
    @DisplayName("every MessageDigest.update hook feeds the digest the bytes it was given")
    void updateHooksFeedTheDigest() {
        assertSameAccumulation("update(byte[])",
                digest -> AgentSharedInstanceHooks.update(digest, PAYLOAD),
                digest -> digest.update(PAYLOAD));
        assertSameAccumulation("update(byte)",
                digest -> AgentSharedInstanceHooks.update(digest, (byte) 7),
                digest -> digest.update((byte) 7));
        assertSameAccumulation("update(byte[], int, int)",
                digest -> AgentSharedInstanceHooks.update(digest, PAYLOAD, 1, 4),
                digest -> digest.update(PAYLOAD, 1, 4));
        assertSameAccumulation("update(ByteBuffer)",
                digest -> AgentSharedInstanceHooks.update(digest, ByteBuffer.wrap(PAYLOAD)),
                digest -> digest.update(ByteBuffer.wrap(PAYLOAD)));
    }

    @Test
    @DisplayName("digest(byte[], int, int) writes the hash out and returns the length it wrote")
    void rangedDigestWritesAndReturnsLength() throws DigestException {
        MessageDigest viaHook = sha256();
        viaHook.update(PAYLOAD);
        byte[] hookOut = new byte[64];
        int written = AgentSharedInstanceHooks.digest(viaHook, hookOut, 8, 32);

        MessageDigest viaJdk = sha256();
        viaJdk.update(PAYLOAD);
        byte[] jdkOut = new byte[64];
        int expected = viaJdk.digest(jdkOut, 8, 32);

        assertEquals(32, expected, "SHA-256 writes 32 bytes; the fixture is wrong if it does not");
        assertEquals(expected, written,
                "the hook must return the byte count the call it replaced returned; a call site "
                        + "reading a mutated 0 would treat a written hash as empty");
        assertArrayEquals(jdkOut, hookOut,
                "the hook must write the hash into the caller's buffer at the caller's offset");
    }

    @Test
    @DisplayName("Matcher hooks return the match result, including when there is no match")
    void matcherHooksReturnTheMatchResult() {
        Matcher exhausted = PATTERN.matcher("corpus-42");
        assertTrue(exhausted.find(), "the fixture needs the one match consumed first");
        assertFalse(AgentSharedInstanceHooks.find(exhausted),
                "find() past the last match must be false; a hook hard-returning true would send "
                        + "the call site to read a group that is not there");

        Matcher partial = PATTERN.matcher("see corpus-42 here");
        assertFalse(AgentSharedInstanceHooks.matches(partial),
                "matches() is false when the pattern does not span the whole region");
        assertTrue(AgentSharedInstanceHooks.find(partial),
                "the same matcher finds the substring, so the false above is the region rule and "
                        + "not an unmatched fixture");

        Matcher fromIndex = PATTERN.matcher("corpus-42 tail");
        assertFalse(AgentSharedInstanceHooks.find(fromIndex, 10),
                "find(start) past the only match must be false");
        assertTrue(AgentSharedInstanceHooks.find(fromIndex, 0),
                "and true from the start, so the false above is the index and not the pattern");
    }

    @Test
    @DisplayName("SimpleDateFormat hooks return the formatted text and the parsed date")
    void simpleDateFormatHooksReturnTheirResult() throws ParseException {
        SimpleDateFormat format = utcFormat();

        assertEquals("1970-01-02", AgentSharedInstanceHooks.format(format, new Date(DAY_ONE)),
                "the hook must return the text the formatter produced, not an empty string");

        assertEquals(new Date(DAY_ONE), AgentSharedInstanceHooks.parse(format, "1970-01-02"),
                "the hook must return the date the formatter parsed, not null");

        ParsePosition position = new ParsePosition(0);
        assertEquals(new Date(DAY_ONE),
                AgentSharedInstanceHooks.parse(format, "1970-01-02 and more", position),
                "the incremental overload must return its date too");
        assertEquals(10, position.getIndex(),
                "and must have advanced the caller's position, which is the whole point of it");
    }

    @Test
    @DisplayName("every Calendar.set hook applies the fields it was given")
    void calendarSetHooksApplyTheFields() {
        Calendar viaHook = utcCalendar();
        Calendar viaJdk = utcCalendar();
        AgentSharedInstanceHooks.set(viaHook, 2026, Calendar.MARCH, 4);
        viaJdk.set(2026, Calendar.MARCH, 4);
        assertEquals(viaJdk.getTimeInMillis(), viaHook.getTimeInMillis(),
                "set(year, month, date) must reach the calendar; a dropped delegation leaves the "
                        + "caller holding the instant it started with");

        Calendar hookedMinutes = utcCalendar();
        Calendar jdkMinutes = utcCalendar();
        AgentSharedInstanceHooks.set(hookedMinutes, 2026, Calendar.MARCH, 4, 5, 6);
        jdkMinutes.set(2026, Calendar.MARCH, 4, 5, 6);
        assertEquals(jdkMinutes.getTimeInMillis(), hookedMinutes.getTimeInMillis(),
                "and so must the hour-and-minute overload");

        Calendar hookedSeconds = utcCalendar();
        Calendar jdkSeconds = utcCalendar();
        AgentSharedInstanceHooks.set(hookedSeconds, 2026, Calendar.MARCH, 4, 5, 6, 7);
        jdkSeconds.set(2026, Calendar.MARCH, 4, 5, 6, 7);
        assertEquals(jdkSeconds.getTimeInMillis(), hookedSeconds.getTimeInMillis(),
                "and so must the one that carries seconds");

        Calendar hookedField = utcCalendar();
        Calendar jdkField = utcCalendar();
        AgentSharedInstanceHooks.set(hookedField, Calendar.YEAR, 2026);
        jdkField.set(Calendar.YEAR, 2026);
        assertEquals(jdkField.getTimeInMillis(), hookedField.getTimeInMillis(),
                "and so must the one-field form");
        assertEquals(2026, AgentSharedInstanceHooks.get(hookedField, Calendar.YEAR),
                "get must return the field it read, not a fabricated zero");
    }
}
