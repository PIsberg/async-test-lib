package com.example.agentfixture;

import java.text.DecimalFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caches four stateful JDK objects in fields and uses them from every thread.
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

    /** {@return how many calls the race broke, which is how often the bug showed itself} */
    public int racesAbsorbed() {
        return racesAbsorbed.get();
    }
}
