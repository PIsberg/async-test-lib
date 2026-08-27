package com.example.agentfixture;

import java.text.DecimalFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Caches four stateful JDK objects in fields and uses them from every thread.
 *
 * <p>Each field is the same mistake in a different type: the object is expensive or awkward to
 * build, so it is hoisted, and the class quietly stops being safe to call concurrently. None of it
 * is declared to the library and no test calls a {@code record} method - the agent substitutes the
 * call sites, which is the only place the instance and the calling thread are both in hand.
 */
public class SharedStatefulJdkBean {

    private final Calendar calendar = Calendar.getInstance(Locale.ROOT);
    private final StringBuilder builder = new StringBuilder();
    private final DecimalFormat decimals = new DecimalFormat("#.##");

    /** Reads a field of the one shared {@link Calendar}. @return the year */
    public int year() {
        return calendar.get(Calendar.YEAR);
    }

    /** Appends to the one shared {@link StringBuilder}. */
    public void append() {
        builder.append("x");
    }

    /** Formats through the one shared {@link DecimalFormat}. @return the formatted number */
    public String money() {
        return decimals.format(12.345d);
    }
}
