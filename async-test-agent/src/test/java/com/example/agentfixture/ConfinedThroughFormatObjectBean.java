package com.example.agentfixture;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.Format;
import java.text.MessageFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

/**
 * The call sites of {@link SharedThroughFormatObjectBean} on formats no two threads share, plus the
 * shared formats a {@code Format}-typed entry must not report.
 *
 * <p>An entry on {@code java.text.Format} matches every format there is. The receivers it brings
 * in that are shared here on purpose: the {@code Format} view of a {@code DateTimeFormatter},
 * which is immutable, and a {@code DecimalFormat} and a {@code MessageFormat} used only under
 * their own monitor, which is the documented way to share one.
 */
public class ConfinedThroughFormatObjectBean {

    /** An immutable formatter behind the {@code Format} type, shared by every thread. */
    private final Format isoDate = DateTimeFormatter.ISO_LOCAL_DATE.toFormat();

    /** Shared, and used only under its own monitor. */
    private final DecimalFormat guardedMoney = new DecimalFormat("#,##0.00");

    /** Shared, and used only under its own monitor. */
    private final MessageFormat guardedMessage = new MessageFormat("{0} has {1} items", Locale.ROOT);

    /** Formats a {@code BigDecimal} through a {@code DecimalFormat} this call built. */
    public void formatDecimal() {
        new DecimalFormat("#,##0.00").format(new BigDecimal("12.50"));
    }

    /** Parses through {@code parseObject} on a {@code DecimalFormat} this call built. */
    public void parseDecimalObject() throws ParseException {
        new DecimalFormat("#,##0.00").parseObject("12.50");
    }

    /** Formats a date passed as an {@code Object} through a {@code SimpleDateFormat} this call built. */
    public void formatDateObject() {
        Object when = new Date(0L);
        new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(when);
    }

    /** Formats and parses through a {@code MessageFormat} this call built. */
    public void formatAndParseMessage() throws ParseException {
        MessageFormat mine = new MessageFormat("{0} has {1} items", Locale.ROOT);
        mine.parse(mine.format(new Object[] {"cart", 3}));
    }

    /** Formats through the static {@code MessageFormat.format}, which builds its own instance. */
    public void formatMessageStatically() {
        MessageFormat.format("{0} has {1} items", "cart", 3);
    }

    /** Formats through the shared, immutable {@code DateTimeFormatter} view. */
    public void formatThroughSharedImmutableFormat() {
        isoDate.format(LocalDate.EPOCH);
    }

    /** Formats through the shared {@code DecimalFormat} under its own monitor. */
    public void formatDecimalUnderItsMonitor() {
        synchronized (guardedMoney) {
            guardedMoney.format(new BigDecimal("12.50"));
        }
    }

    /** Formats through the shared {@code MessageFormat} under its own monitor. */
    public void formatMessageUnderItsMonitor() {
        synchronized (guardedMessage) {
            guardedMessage.format(new Object[] {"cart", 3});
        }
    }
}
