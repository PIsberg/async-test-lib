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
import java.util.zip.CRC32;
import java.util.zip.Checksum;
import java.util.zip.Deflater;

/**
 * The correct twin of {@link SharedStatefulJdkBean}: one instance per call, never shared.
 *
 * <p>Building the object inside the method is the fix most codebases apply, and it must stay
 * silent. The agent substitutes exactly the same call sites here, so the difference the detectors
 * have to see is not which instruction ran but how many threads touched one instance. A finding
 * here would put a false positive on every correctly written use in a woven codebase, which is a
 * far larger population than the buggy one.
 */
public class ConfinedStatefulJdkBean {

    /**
     * {@return the year, from a calendar this call owns}, moved first through every mutator the
     * agent weaves (#820), so the confined direction covers them too
     */
    public int year() {
        Calendar mine = Calendar.getInstance(Locale.ROOT);
        mine.add(Calendar.DAY_OF_MONTH, 1);
        mine.roll(Calendar.MONTH, 1);
        mine.roll(Calendar.HOUR_OF_DAY, true);
        mine.setTimeInMillis(86_400_000L);
        mine.setTime(new java.util.Date(0L));
        mine.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        mine.clear(Calendar.HOUR_OF_DAY);
        mine.clear();
        mine.setTimeInMillis(System.currentTimeMillis());
        return mine.get(Calendar.YEAR);
    }

    /** {@return text built by a builder this call owns} */
    public String append() {
        return new StringBuilder().append("x").append(1).toString();
    }

    /** {@return a number formatted by a format this call owns} */
    public String money() {
        return new DecimalFormat("#.##").format(12.345d);
    }

    /** {@return a byte written and read through a buffer this call owns} */
    public byte buffer() {
        ByteBuffer mine = ByteBuffer.allocate(8);
        mine.clear();
        mine.put((byte) 1);
        mine.flip();
        return mine.get();
    }

    /** {@return encoded bytes from an encoder this call owns} */
    public int encode() {
        try {
            CharsetEncoder mine = StandardCharsets.UTF_8.newEncoder();
            return mine.encode(CharBuffer.wrap("hi")).remaining();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@return checksum computed on an instance this call owns} */
    public long checksum() {
        Checksum mine = new CRC32();
        mine.update(1);
        return mine.getValue();
    }

    /** {@return compressed byte count from a deflater this call owns} */
    public int deflate() {
        Deflater mine = new Deflater();
        try {
            mine.setInput(new byte[]{1, 2, 3, 4});
            mine.finish();
            return mine.deflate(new byte[16]);
        } finally {
            mine.end();
        }
    }

    /** {@return a random int from a generator this call owns} */
    public int randomInt() {
        return new SplittableRandom(1L).nextInt();
    }
}
