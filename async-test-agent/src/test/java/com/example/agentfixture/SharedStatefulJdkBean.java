package com.example.agentfixture;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.Checksum;
import java.util.zip.Deflater;

/**
 * Caches stateful JDK objects in fields and uses them from every thread.
 *
 * <p>Each field is the same mistake in a different type: the object is expensive or awkward to
 * build, so it is hoisted, and the class quietly stops being safe to call concurrently. None of it
 * is declared to the library and no test calls a {@code record} method - the agent substitutes the
 * call sites, which is the only place the instance and the calling thread are both in hand.
 *
 * <p>Each method absorbs what the race throws. Sharing these types is not merely unsound, it
 * fails: a {@code StringBuilder} appended to by four threads can throw out of its own array copy
 * ("last destination index 35 out of bounds for byte[34]" on a macOS leg), and a shared
 * {@code Calendar} or {@code DecimalFormat} can do the same inside its field arithmetic. That is
 * the bug doing what the bug does, after the substituted call site has already recorded the
 * access, and letting it out of the body would fail the test for succeeding. The corpus rows for
 * the same types swallow for the same reason.
 */
public class SharedStatefulJdkBean {

    private final Calendar calendar = Calendar.getInstance(Locale.ROOT);
    private final StringBuilder builder = new StringBuilder();
    private final DecimalFormat decimals = new DecimalFormat("#.##");
    private final ByteBuffer buffer = ByteBuffer.allocate(64);
    private final CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
    private final Checksum checksum = new CRC32();
    private final Deflater deflater = new Deflater();
    private final SplittableRandom splittableRandom = new SplittableRandom(1L);

    /** How many times a shared object threw out of its own internals mid-race. */
    private final AtomicInteger racesAbsorbed = new AtomicInteger();

    /** Reads a field of the one shared {@link Calendar}. @return the year */
    public int year() {
        try {
            return calendar.get(Calendar.YEAR);
        } catch (RuntimeException raced) {
            racesAbsorbed.incrementAndGet();
            return -1;
        }
    }

    /** Appends to the one shared {@link StringBuilder}. */
    public void append() {
        try {
            builder.append("x");
        } catch (RuntimeException raced) {
            racesAbsorbed.incrementAndGet();
        }
    }

    /** Formats through the one shared {@link DecimalFormat}. @return the formatted number */
    public String money() {
        try {
            return decimals.format(12.345d);
        } catch (RuntimeException raced) {
            racesAbsorbed.incrementAndGet();
            return "";
        }
    }

    /** Writes and reads through the one shared {@link ByteBuffer}. @return the byte read */
    public byte buffer() {
        try {
            buffer.clear();
            buffer.put((byte) 1);
            buffer.flip();
            return buffer.get();
        } catch (RuntimeException raced) {
            racesAbsorbed.incrementAndGet();
            return 0;
        }
    }

    /** Encodes through the one shared {@link CharsetEncoder}. @return the encoded capacity */
    public int encode() {
        try {
            return encoder.encode(CharBuffer.wrap("hi")).remaining();
        } catch (CharacterCodingException | RuntimeException raced) {
            racesAbsorbed.incrementAndGet();
            return 0;
        }
    }

    /** Updates and reads the one shared {@link Checksum}. @return the checksum value */
    public long checksum() {
        try {
            checksum.update(1);
            return checksum.getValue();
        } catch (RuntimeException raced) {
            racesAbsorbed.incrementAndGet();
            return 0L;
        }
    }

    /** Compresses through the one shared {@link Deflater}. @return the bytes written */
    public int deflate() {
        try {
            deflater.reset();
            deflater.setInput(new byte[]{1, 2, 3, 4});
            deflater.finish();
            return deflater.deflate(new byte[16]);
        } catch (RuntimeException raced) {
            racesAbsorbed.incrementAndGet();
            return 0;
        }
    }

    /** Draws from the one shared {@link SplittableRandom}. @return the random int */
    public int randomInt() {
        try {
            return splittableRandom.nextInt();
        } catch (RuntimeException raced) {
            racesAbsorbed.incrementAndGet();
            return 0;
        }
    }

    /** {@return how many calls the race broke, which is how often the bug showed itself} */
    public int racesAbsorbed() {
        return racesAbsorbed.get();
    }
}
