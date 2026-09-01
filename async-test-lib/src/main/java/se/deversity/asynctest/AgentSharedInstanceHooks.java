package se.deversity.asynctest;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.security.MessageDigest;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.nio.ByteBuffer;
import java.security.DigestException;
import java.util.Date;
import java.util.Formatter;
import java.util.Locale;
import java.util.regex.Matcher;

import se.deversity.asynctest.diagnostics.CalendarDetector;
import se.deversity.asynctest.diagnostics.SharedDecimalFormatDetector;
import se.deversity.asynctest.diagnostics.SharedFormatterDetector;
import se.deversity.asynctest.diagnostics.SharedMatcherDetector;
import se.deversity.asynctest.diagnostics.SharedMessageDigestDetector;
import se.deversity.asynctest.diagnostics.SimpleDateFormatDetector;
import se.deversity.asynctest.diagnostics.StringBuilderDetector;
import se.deversity.vibetags.annotations.AIContract;

/**
 * Hooks for JDK types that are not thread safe and carry no way to say so.
 *
 * <h2>Why these three</h2>
 *
 * <p>{@code SimpleDateFormat}, {@code Matcher} and {@code MessageDigest} share a shape:
 * each keeps mutable parsing or digest state inside the instance, each is documented as unsafe for
 * concurrent use, and each is routinely cached in a static field because constructing one is
 * expensive. That combination is one of the oldest concurrency bugs in Java, and until now this
 * library could only see it if the test author called a {@code record} method by hand, which means
 * seeing it only when they already suspected it.
 *
 * <p>The mechanism is the one {@link AgentCollectionHooks} documents: substituting the invocation
 * with a static hook whose first parameter is the receiver hands the instance over without spilling
 * arguments, so the operand stack shape is unchanged and no member is added.
 *
 * <h2>Why the shared-instance family and not every stateful JDK type</h2>
 *
 * <p>Each entry costs a rewritten instruction in every woven method that calls it, so the table is
 * not a place to be generous. These three earn it by being both common and unambiguous: unlike
 * {@code Random}, none of them has a thread-safe subclass that a call site could be holding, so a
 * substituted call cannot be recording an instance that was safe all along. {@code ThreadLocalRandom}
 * is exactly that hazard, which is why {@code Random} is absent here.
 *
 * <p>Sharing is decided by the detector, not by this class. A hook fires on every call, and the
 * detector reports only when it has seen more than one thread touch the same instance, so a
 * confined formatter used a thousand times costs a map lookup and produces nothing.
 *
 * @since 1.10.0
 */
@AIContract(reason = "Called from bytecode the agent rewrites: the method names and erased signatures here are matched by CollectionAccessWeaver.SHARED_INSTANCE_ENTRIES and cannot change independently of it. Every hook must perform the original operation and propagate its exceptions unchanged, and must record before delegating only where the original cannot throw first - the detector's question is 'did two threads touch this instance', which a call that threw still answers. The receiver types are deliberately concrete and free of thread-safe subclasses: adding one that has a safe subclass, Random being the standing example with ThreadLocalRandom, turns every substituted call site into a potential false positive on correct code.")
public final class AgentSharedInstanceHooks {

    private AgentSharedInstanceHooks() {
    }

    /**
     * Weaves {@code SimpleDateFormat.format(Date)}.
     *
     * @param receiver the formatter
     * @param date     the date to format
     * @return the formatted text
     */
    public static String format(SimpleDateFormat receiver, Date date) {
        SimpleDateFormatDetector detector = AsyncTestContext.currentSimpleDateFormatDetector();
        if (detector != null) {
            detector.recordFormat(receiver, receiver.getClass().getName());
        }
        return receiver.format(date);
    }

    /**
     * Weaves {@code SimpleDateFormat.parse(String)}.
     *
     * @param receiver the formatter
     * @param source   the text to parse
     * @return the parsed date
     * @throws ParseException if the text cannot be parsed
     */
    public static Date parse(SimpleDateFormat receiver, String source) throws ParseException {
        SimpleDateFormatDetector detector = AsyncTestContext.currentSimpleDateFormatDetector();
        if (detector != null) {
            detector.recordParse(receiver, receiver.getClass().getName());
        }
        return receiver.parse(source);
    }

    /**
     * Weaves {@code SimpleDateFormat.parse(String, ParsePosition)}.
     *
     * <p>The incremental parse, used when walking a string containing several dates. It advances
     * the position object and drives the same internal calendar the other overload does, and it
     * was not woven (#434).
     *
     * @param receiver the formatter
     * @param source   the text to parse
     * @param position where to start, updated to where parsing stopped
     * @return the parsed date, or {@code null} if the text did not match
     */
    public static Date parse(SimpleDateFormat receiver, String source, ParsePosition position) {
        SimpleDateFormatDetector detector = AsyncTestContext.currentSimpleDateFormatDetector();
        if (detector != null) {
            detector.recordParse(receiver, receiver.getClass().getName());
        }
        return receiver.parse(source, position);
    }

    /**
     * Weaves {@code Matcher.find()}.
     *
     * @param receiver the matcher
     * @return whether a match was found
     */
    public static boolean find(Matcher receiver) {
        recordMatcher(receiver);
        return receiver.find();
    }

    /**
     * Weaves {@code Matcher.matches()}.
     *
     * @param receiver the matcher
     * @return whether the whole region matched
     */
    public static boolean matches(Matcher receiver) {
        recordMatcher(receiver);
        return receiver.matches();
    }

    /**
     * Weaves {@code Matcher.group()}.
     *
     * @param receiver the matcher
     * @return the matched subsequence
     */
    public static String group(Matcher receiver) {
        recordMatcher(receiver);
        return receiver.group();
    }

    /**
     * Weaves {@code Matcher.group(int)}.
     *
     * <p>{@code find()} then {@code group(1)} is the standard idiom, and the group-taking overload
     * was the one not woven - so the most common way of reading a shared matcher's result was
     * unobserved while the zero-argument form was seen (#434).
     *
     * @param receiver the matcher
     * @param group    the group index
     * @return the matched subsequence
     */
    public static String group(Matcher receiver, int group) {
        recordMatcher(receiver);
        return receiver.group(group);
    }

    /**
     * Weaves {@code Matcher.group(String)}, the named-group form of the overload above.
     *
     * @param receiver the matcher
     * @param name     the group name
     * @return the matched subsequence
     */
    public static String group(Matcher receiver, String name) {
        recordMatcher(receiver);
        return receiver.group(name);
    }

    /**
     * Weaves {@code Matcher.find(int)}, which resets the matcher before searching.
     *
     * @param receiver the matcher
     * @param start    the index to start from
     * @return whether a match was found
     */
    public static boolean find(Matcher receiver, int start) {
        recordMatcher(receiver);
        return receiver.find(start);
    }

    private static void recordMatcher(Matcher receiver) {
        SharedMatcherDetector detector = AsyncTestContext.currentSharedMatcherDetector();
        if (detector != null) {
            detector.recordAccess(receiver, receiver.getClass().getName(), Thread.currentThread());
        }
    }

    /**
     * Weaves {@code MessageDigest.update(byte[])}.
     *
     * @param receiver the digest
     * @param input    the bytes to add
     */
    public static void update(MessageDigest receiver, byte[] input) {
        recordDigest(receiver);
        receiver.update(input);
    }

    /**
     * Weaves {@code MessageDigest.digest()}.
     *
     * @param receiver the digest
     * @return the computed hash
     */
    public static byte[] digest(MessageDigest receiver) {
        recordDigest(receiver);
        return receiver.digest();
    }

    /**
     * Weaves {@code MessageDigest.digest(byte[])}.
     *
     * @param receiver the digest
     * @param input    the final bytes to add before computing
     * @return the computed hash
     */
    public static byte[] digest(MessageDigest receiver, byte[] input) {
        recordDigest(receiver);
        return receiver.digest(input);
    }

    /**
     * Weaves {@code MessageDigest.update(byte)}.
     *
     * @param receiver the digest
     * @param input    the byte to accumulate
     */
    public static void update(MessageDigest receiver, byte input) {
        recordDigest(receiver);
        receiver.update(input);
    }

    /**
     * Weaves {@code MessageDigest.update(byte[], int, int)}, the ranged form.
     *
     * @param receiver the digest
     * @param input    the buffer to accumulate from
     * @param offset   where to start
     * @param length   how many bytes
     */
    public static void update(MessageDigest receiver, byte[] input, int offset, int length) {
        recordDigest(receiver);
        receiver.update(input, offset, length);
    }

    /**
     * Weaves {@code MessageDigest.update(ByteBuffer)}.
     *
     * @param receiver the digest
     * @param input    the buffer to accumulate from
     */
    public static void update(MessageDigest receiver, ByteBuffer input) {
        recordDigest(receiver);
        receiver.update(input);
    }

    /**
     * Weaves {@code MessageDigest.digest(byte[], int, int)}.
     *
     * @param receiver the digest
     * @param output   where to write the digest
     * @param offset   where to start writing
     * @param length   how much room there is
     * @return the number of bytes written
     * @throws DigestException if the output buffer is too small
     */
    public static int digest(MessageDigest receiver, byte[] output, int offset, int length)
            throws DigestException {
        recordDigest(receiver);
        return receiver.digest(output, offset, length);
    }

    private static void recordDigest(MessageDigest receiver) {
        SharedMessageDigestDetector detector =
                AsyncTestContext.currentSharedMessageDigestDetector();
        if (detector != null) {
            // getAlgorithm() is the label a reader wants and the instance already holds it, so
            // this costs a field read rather than the string concatenation a prettier name would.
            detector.recordAccess(receiver, receiver.getAlgorithm(), Thread.currentThread());
        }
    }

    /**
     * Weaves {@code Calendar.get(int)}.
     *
     * @param receiver the calendar
     * @param field    the field to read
     * @return the field's value
     */
    public static int get(Calendar receiver, int field) {
        CalendarDetector detector = AsyncTestContext.currentCalendarDetector();
        if (detector != null) {
            detector.recordGet(receiver, receiver.getClass().getName());
        }
        return receiver.get(field);
    }

    /**
     * Weaves {@code Calendar.set(int, int)}.
     *
     * @param receiver the calendar
     * @param field    the field to write
     * @param value    the value to write
     */
    public static void set(Calendar receiver, int field, int value) {
        CalendarDetector detector = AsyncTestContext.currentCalendarDetector();
        if (detector != null) {
            detector.recordSet(receiver, receiver.getClass().getName());
        }
        receiver.set(field, value);
    }

    /**
     * Weaves {@code Calendar.set(int, int, int)}.
     *
     * <p>The three date-setting overloads below are how calendars are actually populated -
     * {@code set(field, value)} one field at a time is the rarer shape - and none of them was
     * woven (#434).
     *
     * @param receiver the calendar
     * @param year     the calendar year, as Calendar.YEAR takes it
     * @param month    the zero-based month, as Calendar.MONTH takes it
     * @param date     the day of month
     */
    public static void set(Calendar receiver, int year, int month, int date) {
        recordCalendarSet(receiver);
        receiver.set(year, month, date);
    }

    /**
     * Weaves {@code Calendar.set(int, int, int, int, int)}.
     *
     * @param receiver the calendar
     * @param year     the calendar year, as Calendar.YEAR takes it
     * @param month    the zero-based month, as Calendar.MONTH takes it
     * @param date     the day of month
     * @param hour     the hour of day
     * @param minute   the minutes past the hour
     */
    public static void set(Calendar receiver, int year, int month, int date, int hour,
                           int minute) {
        recordCalendarSet(receiver);
        receiver.set(year, month, date, hour, minute);
    }

    /**
     * Weaves {@code Calendar.set(int, int, int, int, int, int)}.
     *
     * @param receiver the calendar
     * @param year     the calendar year, as Calendar.YEAR takes it
     * @param month    the zero-based month, as Calendar.MONTH takes it
     * @param date     the day of month
     * @param hour     the hour of day
     * @param minute   the minutes past the hour
     * @param second   the seconds past the minute
     */
    public static void set(Calendar receiver, int year, int month, int date, int hour,
                           int minute, int second) {
        recordCalendarSet(receiver);
        receiver.set(year, month, date, hour, minute, second);
    }

    private static void recordCalendarSet(Calendar receiver) {
        CalendarDetector detector = AsyncTestContext.currentCalendarDetector();
        if (detector != null) {
            detector.recordSet(receiver, receiver.getClass().getName());
        }
    }

    /**
     * Weaves {@code StringBuilder.append(String)}.
     *
     * @param receiver the builder
     * @param value    the text to append
     * @return the builder, so the call chain is unchanged
     */
    public static StringBuilder append(StringBuilder receiver, String value) {
        recordBuilder(receiver);
        return receiver.append(value);
    }

    /**
     * Weaves {@code StringBuilder.append(int)}.
     *
     * @param receiver the builder
     * @param value    the number to append
     * @return the builder, so the call chain is unchanged
     */
    public static StringBuilder append(StringBuilder receiver, int value) {
        recordBuilder(receiver);
        return receiver.append(value);
    }

    /**
     * Weaves {@code StringBuilder.append(char)}.
     *
     * <p>The five overloads below carry no new argument about correctness. They exist because the
     * weaver matches an exact descriptor, so a shared builder appended to with a {@code char} was
     * invisible while the same builder appended to with a {@code String} was not - and
     * {@code append(char)} reads {@code count}, writes the array and writes {@code count} back
     * exactly as the others do. The gap was found by a corpus row that had to fire and did not
     * (#434).
     *
     * @param receiver the builder
     * @param value    the character to append
     * @return the builder, so the call chain is unchanged
     */
    public static StringBuilder append(StringBuilder receiver, char value) {
        recordBuilder(receiver);
        return receiver.append(value);
    }

    /**
     * Weaves {@code StringBuilder.append(long)}.
     *
     * @param receiver the builder
     * @param value    the number to append
     * @return the builder, so the call chain is unchanged
     */
    public static StringBuilder append(StringBuilder receiver, long value) {
        recordBuilder(receiver);
        return receiver.append(value);
    }

    /**
     * Weaves {@code StringBuilder.append(double)}.
     *
     * @param receiver the builder
     * @param value    the number to append
     * @return the builder, so the call chain is unchanged
     */
    public static StringBuilder append(StringBuilder receiver, double value) {
        recordBuilder(receiver);
        return receiver.append(value);
    }

    /**
     * Weaves {@code StringBuilder.append(boolean)}.
     *
     * @param receiver the builder
     * @param value    the flag to append
     * @return the builder, so the call chain is unchanged
     */
    public static StringBuilder append(StringBuilder receiver, boolean value) {
        recordBuilder(receiver);
        return receiver.append(value);
    }

    /**
     * Weaves {@code StringBuilder.append(Object)}.
     *
     * @param receiver the builder
     * @param value    the value to append
     * @return the builder, so the call chain is unchanged
     */
    public static StringBuilder append(StringBuilder receiver, Object value) {
        recordBuilder(receiver);
        return receiver.append(value);
    }

    /**
     * Weaves {@code StringBuilder.append(CharSequence)}.
     *
     * @param receiver the builder
     * @param value    the sequence to append
     * @return the builder, so the call chain is unchanged
     */
    public static StringBuilder append(StringBuilder receiver, CharSequence value) {
        recordBuilder(receiver);
        return receiver.append(value);
    }

    private static void recordBuilder(StringBuilder receiver) {
        StringBuilderDetector detector = AsyncTestContext.currentStringBuilderDetector();
        if (detector != null) {
            detector.recordAppend(receiver, receiver.getClass().getName());
        }
    }

    /**
     * Weaves {@code NumberFormat.format(long)}.
     *
     * <p>Formatting an integral value is at least as common as formatting a {@code double}, and
     * only the {@code double} overload was woven (#434).
     *
     * @param receiver the format
     * @param value    the number to format
     * @return the formatted text
     */
    public static String format(NumberFormat receiver, long value) {
        recordNumberFormat(receiver);
        return receiver.format(value);
    }

    /**
     * Weaves {@code NumberFormat.format(double)}, which covers {@code DecimalFormat}.
     *
     * @param receiver the format
     * @param value    the number to format
     * @return the formatted text
     */
    public static String format(NumberFormat receiver, double value) {
        recordNumberFormat(receiver);
        return receiver.format(value);
    }

    private static void recordNumberFormat(NumberFormat receiver) {
        SharedDecimalFormatDetector detector =
                AsyncTestContext.currentSharedDecimalFormatDetector();
        if (detector != null) {
            detector.recordAccess(receiver, receiver.getClass().getName(),
                    Thread.currentThread());
        }
    }

    /**
     * Weaves {@code java.util.Formatter.format(String, Object...)}.
     *
     * @param receiver the formatter
     * @param format   the format string
     * @param args     the format arguments
     * @return the formatter, so the call chain is unchanged
     */
    @SuppressFBWarnings("FORMAT_STRING_MANIPULATION")
    // The format string is the call site's own and reaches the original method unchanged: this
    // hook replaces the invocation, it does not compose the string. SpotBugs sees a non-literal
    // format argument and cannot see that it is the same value the user already passed, so the
    // finding is true of every substitution hook and false of all of them.
    public static Formatter format(Formatter receiver, String format, Object... args) {
        recordFormatter(receiver);
        return receiver.format(format, args);
    }

    /**
     * Weaves {@code Formatter.format(Locale, String, Object...)}.
     *
     * <p>The locale-taking overload is the one an internationalised codebase actually calls, and
     * it was invisible while its two-argument sibling was woven. Same receiver, same interleaved
     * output, different descriptor (#434).
     *
     * @param receiver the formatter
     * @param locale   the locale to format with
     * @param format   the format string
     * @param args     the format arguments
     * @return the formatter, so the call chain is unchanged
     */
    @SuppressFBWarnings("FORMAT_STRING_MANIPULATION")
    // Same reasoning as the overload above: the format string is the call site's own and reaches
    // the original method unchanged.
    public static Formatter format(Formatter receiver, Locale locale, String format,
                                   Object... args) {
        recordFormatter(receiver);
        return receiver.format(locale, format, args);
    }

    private static void recordFormatter(Formatter receiver) {
        SharedFormatterDetector detector = AsyncTestContext.currentSharedFormatterDetector();
        if (detector != null) {
            detector.recordAccess(receiver, receiver.getClass().getName(),
                    Thread.currentThread());
        }
    }
}
