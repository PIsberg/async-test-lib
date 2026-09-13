package com.example.agentfixture;

import java.io.IOException;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shares three stateful JDK objects, each reached only through a wider static type.
 *
 * <p>This is how library code holds them. Jackson keeps a {@code SimpleDateFormat} in a
 * {@code DateFormat} field, Guava's {@code Joiner} appends to a {@code StringBuilder} through
 * {@code Appendable}, and Spring's {@code NumberUtils} parses with whatever {@code NumberFormat} it
 * is handed. The weaver matched the call site's owner against the concrete type, so all three
 * were invisible however many threads shared the instance (#542).
 */
public class SharedThroughWiderTypeBean {

    private final DateFormat dates = new SimpleDateFormat("yyyy-MM-dd");
    private final NumberFormat numbers = new DecimalFormat("#.##");
    private final Appendable text = new StringBuilder();

    /** How many calls the race broke. */
    private final AtomicInteger racesAbsorbed = new AtomicInteger();

    /** Formats through the shared {@code SimpleDateFormat}, held as a {@code DateFormat}. */
    public void formatDate() {
        try {
            dates.format(new Date(0L));
        } catch (RuntimeException raced) {
            racesAbsorbed.incrementAndGet();
        }
    }

    /** Parses with the shared {@code DecimalFormat}, held as a {@code NumberFormat}. */
    public void parseNumber() {
        try {
            numbers.parse("12.34");
        } catch (ParseException | RuntimeException raced) {
            racesAbsorbed.incrementAndGet();
        }
    }

    /** Appends to the shared {@code StringBuilder}, held as an {@code Appendable}. */
    public void appendText() {
        try {
            text.append("x");
        } catch (IOException | RuntimeException raced) {
            racesAbsorbed.incrementAndGet();
        }
    }

    /** {@return how many calls the race broke} */
    public int racesAbsorbed() {
        return racesAbsorbed.get();
    }
}
