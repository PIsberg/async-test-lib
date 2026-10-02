package com.example.agentfixture;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Moves one shared {@link Calendar} with every mutator except {@code set}, and never reads it (#820).
 *
 * <p>The agent wove only {@code get} and the {@code set} overloads, so a calendar driven this way
 * was invisible: no call site here was substituted and no record reached the detector. Each method
 * absorbs what the race throws, for the reason {@link SharedStatefulJdkBean} gives.
 */
public class SharedCalendarMutatorBean {

    private final Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);

    /** Advances the shared calendar through each mutator in turn. */
    public void advance() {
        try {
            calendar.add(Calendar.DAY_OF_MONTH, 1);
            calendar.roll(Calendar.MONTH, 1);
            calendar.roll(Calendar.HOUR_OF_DAY, true);
            calendar.setTimeInMillis(86_400_000L);
            calendar.setTime(new Date(0L));
            calendar.setTimeZone(TimeZone.getTimeZone("UTC"));
            calendar.clear(Calendar.HOUR_OF_DAY);
            calendar.clear();
        } catch (RuntimeException raced) {
            // The bug doing what the bug does; the substituted call sites have recorded already.
        }
    }
}
