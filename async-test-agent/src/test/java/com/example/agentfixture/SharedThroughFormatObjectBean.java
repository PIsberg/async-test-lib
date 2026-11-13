package com.example.agentfixture;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.Format;
import java.text.MessageFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One unsafe format per field, shared by every thread and reached only through the calls
 * {@code java.text.Format} declares: {@code format(Object)} and {@code parseObject(String)}.
 *
 * <p>{@code format(Object)} is how a {@code BigDecimal} is formatted, so it is the ordinary call on
 * a {@code DecimalFormat} in money code. The weaver listed {@code format(double)},
 * {@code format(long)} and {@code format(Date)} only, so each of these was invisible however many
 * threads shared the instance, and a {@code MessageFormat} was not listed at all (#1319).
 */
public class SharedThroughFormatObjectBean {

    private final DecimalFormat money = new DecimalFormat("#,##0.00");
    private final Format moneyAsFormat = new DecimalFormat("#,##0.00");
    private final DecimalFormat parsedMoney = new DecimalFormat("#,##0.00");
    private final SimpleDateFormat dates = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
    private final SimpleDateFormat parsedDates = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
    private final MessageFormat message = new MessageFormat("{0} has {1} items", Locale.ROOT);
    private final MessageFormat parsedMessage = new MessageFormat("{0} has {1} items", Locale.ROOT);

    /** How many calls the race broke. */
    private final AtomicInteger racesAbsorbed = new AtomicInteger();

    /** Formats a {@code BigDecimal} through the shared {@code DecimalFormat}. */
    public void formatDecimal() {
        absorbing(() -> money.format(new BigDecimal("12.50")));
    }

    /** The same call with the shared {@code DecimalFormat} held as a {@code Format}. */
    public void formatDecimalHeldAsFormat() {
        absorbing(() -> moneyAsFormat.format(new BigDecimal("12.50")));
    }

    /** Parses through {@code parseObject} on the shared {@code DecimalFormat}. */
    public void parseDecimalObject() {
        absorbing(() -> parsedMoney.parseObject("12.50"));
    }

    /** Formats a date passed as an {@code Object} through the shared {@code SimpleDateFormat}. */
    public void formatDateObject() {
        Object when = new Date(0L);
        absorbing(() -> dates.format(when));
    }

    /** Parses through {@code parseObject} on the shared {@code SimpleDateFormat}. */
    public void parseDateObject() {
        absorbing(() -> parsedDates.parseObject("2020-01-02"));
    }

    /** Formats through the shared {@code MessageFormat}. */
    public void formatMessage() {
        absorbing(() -> message.format(new Object[] {"cart", 3}));
    }

    /** Parses through the shared {@code MessageFormat}. */
    public void parseMessage() {
        absorbing(() -> parsedMessage.parse("cart has 3 items"));
    }

    /** {@return how many calls the race broke} */
    public int racesAbsorbed() {
        return racesAbsorbed.get();
    }

    private void absorbing(Call call) {
        try {
            call.run();
        } catch (ParseException | RuntimeException raced) {
            racesAbsorbed.incrementAndGet();
        }
    }

    /** A format or parse call. */
    private interface Call {
        Object run() throws ParseException;
    }
}
