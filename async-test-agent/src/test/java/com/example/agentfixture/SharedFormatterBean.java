package com.example.agentfixture;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Caches one {@link SimpleDateFormat} in a field and formats through it from every thread.
 *
 * <p>This is the oldest shape in the catalogue: constructing a formatter is expensive, so somebody
 * hoists it to a field, and the class silently stops being safe to call concurrently. Nothing here
 * declares anything to the library, and no test calls a {@code record} method - the agent
 * substitutes the {@code format} call site, which is the only place the instance and the calling
 * thread are both in hand.
 */
public class SharedFormatterBean {

    private final SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);

    /**
     * {@return the date, formatted through the one shared formatter}
     *
     * @param date the date to format
     */
    public String render(Date date) {
        return formatter.format(date);
    }
}
