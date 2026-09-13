package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The hooks added for #542 do exactly what the call they replace did.
 *
 * <p>They stand in for {@code DateFormat}, {@code Appendable} and {@code NumberFormat.parse} call
 * sites, which are far more common than the concrete-typed ones the table already covered: every
 * {@code Writer} append in a woven class now goes through one of these. A hook that changed a
 * return value or swallowed an {@code IOException} would corrupt the program under test, and only
 * with the agent attached, so each is checked against the receiver kinds it will actually meet,
 * not only the one it records.
 */
class WiderReceiverHooksDelegationTest {

    @Test
    @DisplayName("DateFormat hooks format and parse on any DateFormat, simple or not")
    void dateFormatHooksDelegate() throws ParseException {
        SimpleDateFormat simple = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        simple.setTimeZone(TimeZone.getTimeZone("UTC"));
        DateFormat asWider = simple;
        assertEquals("1970-01-01", AgentSharedInstanceHooks.format(asWider, new Date(0L)));
        assertEquals(new Date(0L), AgentSharedInstanceHooks.parse(asWider, "1970-01-01"));
        ParsePosition position = new ParsePosition(0);
        assertEquals(new Date(0L), AgentSharedInstanceHooks.parse(asWider, "1970-01-01", position));
        assertEquals(10, position.getIndex(), "the position advanced exactly as the JDK moves it");
        assertThrows(ParseException.class,
                () -> AgentSharedInstanceHooks.parse(asWider, "not a date"),
                "a parse failure reaches the caller unchanged");

        DateFormat notSimple = DateFormat.getDateInstance(DateFormat.SHORT, Locale.ROOT);
        assertEquals(notSimple.format(new Date(0L)),
                AgentSharedInstanceHooks.format(notSimple, new Date(0L)),
                "a receiver the hook does not record is still formatted");
    }

    @Test
    @DisplayName("Appendable hooks append to a builder, a buffer and a writer, and rethrow")
    void appendableHooksDelegate() throws IOException {
        StringBuilder builder = new StringBuilder();
        assertSame(builder, AgentSharedInstanceHooks.append((Appendable) builder, "ab"));
        AgentSharedInstanceHooks.append((Appendable) builder, 'c');
        AgentSharedInstanceHooks.append((Appendable) builder, "xdey", 1, 3);
        assertEquals("abcde", builder.toString());

        StringBuffer buffer = new StringBuffer();
        AgentSharedInstanceHooks.append((Appendable) buffer, "ok");
        assertEquals("ok", buffer.toString(), "a StringBuffer is not recorded but is appended to");

        StringWriter writer = new StringWriter();
        AgentSharedInstanceHooks.append((Appendable) writer, 'w');
        assertEquals("w", writer.toString());

        Writer closed = new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) throws IOException {
                throw new IOException("closed");
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        assertThrows(IOException.class, () -> AgentSharedInstanceHooks.append(closed, "x"),
                "an IOException from the receiver reaches the caller unchanged");
    }

    @Test
    @DisplayName("NumberFormat parse hooks return what the format parsed")
    void numberFormatParseHooksDelegate() throws ParseException {
        NumberFormat format = new DecimalFormat("#.##", DecimalFormatSymbols.getInstance(Locale.ROOT));
        assertEquals(12.34, AgentSharedInstanceHooks.parse(format, "12.34").doubleValue());
        ParsePosition position = new ParsePosition(3);
        assertEquals(7L, AgentSharedInstanceHooks.parse(format, "abc7", position).longValue());
        assertEquals(4, position.getIndex());
        assertThrows(ParseException.class, () -> AgentSharedInstanceHooks.parse(format, "x"));
    }
}
