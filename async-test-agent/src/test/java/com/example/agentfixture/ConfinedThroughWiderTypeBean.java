package com.example.agentfixture;

import java.io.IOException;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * The same wider-typed call sites as {@link SharedThroughWiderTypeBean}, on objects no two threads
 * share, plus two shared objects the wider entries must not mistake for the unsafe types.
 *
 * <p>Widening the match to {@code DateFormat} and {@code Appendable} brings in receivers that are
 * nothing to do with the detectors: a {@code DateFormat} subclass that is not a
 * {@code SimpleDateFormat}, and an {@code Appendable} that is a synchronized {@code StringBuffer}.
 * Both are shared here on purpose. The hooks check the runtime type before recording, and this is
 * what that check is for.
 */
public class ConfinedThroughWiderTypeBean {

    /** A DateFormat that is not a SimpleDateFormat, shared by every thread. */
    private final DateFormat notSimple = new EpochDateFormat();

    /** A thread-safe Appendable, shared by every thread. */
    private final Appendable synchronizedText = new StringBuffer();

    /** Formats through a {@code SimpleDateFormat} this call built, held as a {@code DateFormat}. */
    public void formatDate() {
        DateFormat mine = new SimpleDateFormat("yyyy-MM-dd");
        mine.format(new Date(0L));
    }

    /** Parses with a {@code DecimalFormat} this call built, held as a {@code NumberFormat}. */
    public void parseNumber() throws ParseException {
        NumberFormat mine = new DecimalFormat("#.##");
        mine.parse("12.34");
    }

    /** Appends to a {@code StringBuilder} this call built, held as an {@code Appendable}. */
    public void appendText() throws IOException {
        Appendable mine = new StringBuilder();
        mine.append("x");
    }

    /** Formats through the shared DateFormat that is not a SimpleDateFormat. */
    public void formatThroughSharedNonSimpleFormat() {
        notSimple.format(new Date(0L));
    }

    /** Appends to the shared StringBuffer through {@code Appendable}. */
    public void appendToSharedStringBuffer() throws IOException {
        synchronized (synchronizedText) {
            synchronizedText.append("x");
        }
    }

    /** A stateless DateFormat, which is what makes it safe to share. */
    private static final class EpochDateFormat extends DateFormat {

        @Override
        public StringBuffer format(Date date, StringBuffer toAppendTo, FieldPosition fieldPosition) {
            return toAppendTo.append(date.getTime());
        }

        @Override
        public Date parse(String source, ParsePosition pos) {
            pos.setIndex(source.length());
            return new Date(Long.parseLong(source));
        }
    }
}
